package ore

import java.sql.Connection

import scala.concurrent.ExecutionContext
import scala.jdk.DurationConverters._

import ore.db.ModelService
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.service.OreModelService
import ore.discourse.AkkaDiscourseApi.AkkaDiscourseSettings
import ore.discourse.{AkkaDiscourseApi, DiscourseApi, OreDiscourseApi, OreDiscourseApiEnabled}
import ore.models.Job

import akka.actor.{ActorSystem, Terminated}
import cats.effect.{Blocker, ConcurrentEffect, Resource}
import cats.tagless.syntax.all._
import cats.~>
import com.typesafe.scalalogging
import doobie.free.KleisliInterpreter
import doobie.util.ExecutionContexts
import doobie.util.transactor.{Strategy, Transactor}
import slick.jdbc.JdbcDataSource
import zio._
import zio.blocking.Blocking
import zio.interop.catz._

object OreJobProcessorMain extends zio.ManagedApp {
  private val Logger = scalalogging.Logger("OreJobsMain")

  type SlickDb = OrePostgresDriver.backend.DatabaseDef

  implicit def cEffect[R >: ZEnv]: ConcurrentEffect[RIO[R, *]] = {
    implicit val runtime: Runtime[R] = this
    zio.interop.catz.taskEffectInstance
  }

  private val blocker =
    Blocker.liftExecutionContext(this.environment.get[Blocking.Service].blockingExecutor.asEC)

  override def run(args: List[String]): ZManaged[ZEnv, Nothing, ExitCode] = {
    def log(f: scalalogging.Logger => Unit): ZManaged[Any, Nothing, Unit] = ZManaged.fromEffect(UIO(f(Logger)))

    val transactorL       = (slickDbLayer ++ doobieInterpreterLayer ++ connectECLayer) >>> transactorLayer
    val taskModelServiceL = (slickDbLayer ++ transactorL) >>> modelServiceLayer
    val uioModelServiceL  = taskModelServiceL.map(h => Has(h.get.mapK(Lambda[Task ~> UIO](task => task.orDie))))
    val discourseApiL     = (configLayer ++ actorSystemLayer) >>> discourseApiLayer
    val oreDiscourseL     = (discourseApiL ++ configLayer ++ taskModelServiceL) >>> oreDiscourseLayer
    val oreEnvL           = uioModelServiceL ++ configLayer ++ oreDiscourseL

    val all: ZManaged[OreEnv with Has[SlickDb], Nothing, ExitCode] = for {
      maxConnections <- ZManaged.access[Has[SlickDb]](_.get.source.maxConnections.getOrElse(32))
      _              <- log(_.info("Init finished. Starting"))
      _              <- runApp(maxConnections)
    } yield ExitCode.success

    all.provideCustomLayer(oreEnvL ++ slickDbLayer).catchAll(ZManaged.succeed(_))
  }

  private def runApp(maxConnections: Int): ZManaged[OreEnv, Nothing, Unit] =
    ZManaged.environment[OreEnv].flatMap { env =>
      implicit val service: ModelService[UIO] = env.get[ModelService[UIO]]

      val checkAndCreateFibers = for {
        awaitingJobs <- ModelView.now(Job).count(_.state === (Job.JobState.NotStarted: Job.JobState))
        _            <- UIO(Logger.debug(s"Found $awaitingJobs unstarted jobs"))
        _ <- if (awaitingJobs > 0) {
          val fibers = ZIO.foreachPar_(0 until math.max(1, math.min(awaitingJobs, maxConnections - 3))) { _ =>
            JobsProcessor.fiber.sandbox
          }

          fibers.catchAll { cause =>
            Logger.error(s"A fiber died while processing it's jobs\n${cause.prettyPrint}")
            ZIO.unit
          }
        } else ZIO.unit
      } yield ()

      val schedule = Schedule.spaced(env.get[OreJobsConfig].jobs.checkInterval.toJava)

      ZManaged.fromEffect(
        checkAndCreateFibers
          .catchAllCause { cause =>
            Logger.error(s"Encountered an error while checking if there are more jobs\n${cause.prettyPrint}")
            ZIO.unit
          }
          .repeat(schedule)
          .unit
      )
    }

  private def logErrorManaged(msg: String)(e: Throwable): ZManaged[Any, Nothing, ExitCode] = {
    Logger.error(msg, e)
    ZManaged.succeed(ExitCode.failure)
  }

  private def logErrorExitCode(msg: String)(e: Throwable): UIO[ExitCode] = {
    Logger.error(msg, e)
    ZIO.succeed(ExitCode.failure)
  }

  private def logErrorUIO(msg: String)(e: Throwable): UIO[Unit] = {
    Logger.error(msg, e)
    ZIO.succeed(())
  }

  private val slickDbLayer: ZLayer[Any, ExitCode, Has[SlickDb]] =
    ZManaged
      .makeEffect(Database.forConfig("jobs-db", classLoader = this.getClass.getClassLoader))(_.close())
      .flatMapError(logErrorManaged("Failed to connect to db"))
      .toLayer

  private val connectECLayer = ExecutionContexts
    .fixedThreadPool[Task](32)
    .toManaged
    .flatMapError(logErrorManaged("Failed to create doobie transactor"))
    .toLayer

  private val doobieInterpreterLayer: ZLayer[Any, Nothing, Has[KleisliInterpreter[Task]]] =
    ZLayer.succeed(KleisliInterpreter[Task](blocker))

  private val transactorLayer
      : ZLayer[Has[ExecutionContext] with Has[KleisliInterpreter[Task]] with Has[SlickDb], Nothing, Has[
        Transactor[Task]
      ]] =
    ZLayer.fromServices[ExecutionContext, KleisliInterpreter[Task], SlickDb, Transactor[Task]] {
      (connectEC, interpreter, db) =>
        Transactor[Task, JdbcDataSource](
          db.source,
          source => {
            import zio.blocking._
            val acquire = Task(source.createConnection()).on(connectEC)

            def release(c: Connection) = effectBlocking(c.close()).provide(environment)

            Resource.make(acquire)(release)
          },
          interpreter.ConnectionInterpreter,
          Strategy.default
        )
    }

  private val modelServiceLayer: ZLayer[Has[SlickDb] with Has[Transactor[Task]], Nothing, Has[ModelService[Task]]] =
    ZLayer.fromServices[SlickDb, Transactor[Task], ModelService[Task]](new OreModelService(_, _))

  private val actorSystemLayer: ZLayer[Any, Nothing, Has[ActorSystem]] =
    ZManaged
      .make(UIO(ActorSystem("OreJobs"))) { system =>
        val terminate: ZIO[Any, Unit, Terminated] = ZIO
          .fromFuture(ec => system.terminate().map(identity)(ec))
          .flatMapError(logErrorUIO("Error when stopping actor system"))
        terminate.ignore
      }
      .toLayer

  private val configLayer: ZLayer[Any, ExitCode, Has[OreJobsConfig]] =
    ZManaged
      .fromEither(OreJobsConfig.load)
      .flatMapError { es =>
        Logger.error(
          s"Failed to load config:\n${es.prettyPrint(1)}"
        )
        ZManaged.succeed(ExitCode.failure)
      }
      .toLayer

  private val discourseApiLayer: ZLayer[Config with Has[ActorSystem], ExitCode, Has[DiscourseApi[Task]]] =
    ZLayer.fromServicesM[OreJobsConfig, ActorSystem, Any, ExitCode, DiscourseApi[Task]] {
      (config: OreJobsConfig, system: ActorSystem) =>
        implicit val impSystem: ActorSystem = system
        val cfg                             = config.discourse.api
        AkkaDiscourseApi[Task](
          AkkaDiscourseSettings(
            cfg.key,
            cfg.admin,
            config.discourse.baseUrl,
            cfg.breaker.maxFailures,
            cfg.breaker.reset,
            cfg.breaker.timeout
          )
        ).flatMapError(logErrorExitCode("Failed to create forums client"))
    }

  private val oreDiscourseLayer
      : ZLayer[Has[DiscourseApi[Task]] with Has[OreJobsConfig] with Has[ModelService[Task]], ExitCode, Discourse] = {
    def readFile(file: String): Task[String] = {
      fs2.io
        .readInputStream(ZIO(this.getClass.getClassLoader.getResourceAsStream(file)), 1024, blocker)
        .through(fs2.text.utf8Decode)
        .compile
        .string
    }

    ZLayer.fromServicesM[DiscourseApi[Task], OreJobsConfig, ModelService[Task], Any, ExitCode, OreDiscourseApi[Task]] {
      (discourseClient, config, service) =>
        val cfg                                     = config.discourse
        implicit val impService: ModelService[Task] = service
        implicit val impConfig: OreJobsConfig       = config

        val f = for {
          projectTopic <- readFile("discourse/project_topic.md")
          versionPost  <- readFile("discourse/version_post.md")
        } yield new OreDiscourseApiEnabled[Task](
          discourseClient,
          cfg.categoryDefault,
          cfg.categoryDeleted,
          projectTopic,
          versionPost,
          cfg.api.admin
        )

        f.flatMapError(logErrorExitCode("Failed to create ore discourse client"))
    }
  }
}
