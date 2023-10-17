import scala.language.higherKinds

import java.sql.Connection
import java.time.Duration
import javax.inject.Provider

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration

import play.api.cache.caffeine.CaffeineCacheComponents
import play.api.cache.{DefaultSyncCacheApi, SyncCacheApi}
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.slick.evolutions.SlickEvolutionsComponents
import play.api.db.slick.{DatabaseConfigProvider, DbName, SlickComponents}
import play.api.http.{HttpErrorHandler, JsonHttpErrorHandler}
import play.api.i18n.MessagesApi
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.api.{
  ApplicationLoader,
  BuiltInComponentsFromContext,
  LoggerConfigurator,
  OptionalSourceMapper,
  Application => PlayApplication
}
import play.filters.HttpFiltersComponents
import play.filters.cors.{CORSConfigProvider, CORSFilterProvider}
import play.filters.csp.{CSPConfig, CSPFilter, DefaultCSPProcessor, DefaultCSPResultProcessor}
import play.filters.gzip.{GzipFilter, GzipFilterConfig}

import controllers._
import controllers.project.{Projects, Versions}
import controllers.sugar.Bakery
import db.impl.{DbUpdateTask, OreEvolutionsReader}
import db.impl.access.{OrganizationBase, ProjectBase, UserBase}
import db.impl.service.OreModelService
import db.impl.service.OreModelService.F
import filters.LoggingFilter
import form.OreForms
import mail.{EmailFactory, Mailer, SpongeMailer}
import ore.auth.{AkkaSSOApi, AkkaSpongeAuthApi, SSOApi, SpongeAuthApi}
import ore.db.ModelService
import ore.external.Cacher
import ore.markdown.{FlexmarkRenderer, MarkdownRenderer}
import ore.models.project.ProjectTask
import ore.models.project.factory.{OreProjectFactory, ProjectFactory}
import ore.models.project.io.ProjectFiles
import ore.models.user.{FakeUser, UserTask}
import ore.rest.{OreRestfulApiV1, OreRestfulServerV1}
import ore.{OreConfig, OreEnv, StatTracker}
import util.{FileIO, StatusZ, ZIOFileIO}

import ErrorHandler.OreHttpErrorHandler
import akka.actor.ActorSystem
import cats.arrow.FunctionK
import cats.effect.{ContextShift, Resource}
import cats.tagless.syntax.all._
import cats.~>
import com.softwaremill.macwire._
import com.typesafe.scalalogging.Logger
import doobie.util.transactor.Strategy
import doobie.{ExecutionContexts, KleisliInterpreter, Transactor}
import pureconfig.ConfigSource
import slick.basic.{BasicProfile, DatabaseConfig}
import slick.jdbc.{JdbcDataSource, JdbcProfile}
import zio.blocking.Blocking
import zio.interop.catz._
import zio.interop.catz.implicits._
import zio.{ExecutionStrategy, Exit, Runtime, Schedule, Task, UIO, ZEnv, ZIO, ZManaged}

class OreApplicationLoader extends ApplicationLoader {

  override def load(context: ApplicationLoader.Context): PlayApplication = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }

    new OreComponents(context).application
  }
}

class OreComponents(context: ApplicationLoader.Context)
    extends BuiltInComponentsFromContext(context)
    with HttpFiltersComponents
    with AssetsComponents
    with CaffeineCacheComponents
    with SlickComponents
    with SlickEvolutionsComponents
    with EvolutionsComponents {

  val prefix                                = "/"
  override lazy val router: Router          = wire[_root_.router.Routes]
  lazy val apiV2Routes: _root_.apiv2.Routes = wire[_root_.apiv2.Routes]

  use(prefix) //Gets around unused warning
  eager(applicationEvolutions)

  val logger = Logger("Bootstrap")

  override lazy val httpFilters: Seq[EssentialFilter] = {
    val filters              = enabledFilters ++ super.httpFilters
    val enabledFiltersConfig = configuration.get[Seq[String]]("play.filters.enabled")
    val enabledFiltersCode   = filters.map(_.getClass.getName)

    val notEnabledFilters = enabledFiltersConfig.diff(enabledFiltersCode)

    if (notEnabledFilters.nonEmpty) {
      logger.warn(s"Found filters enabled in the config but not in code: $notEnabledFilters")
    }

    filters
  }

  lazy val enabledFilters: Seq[EssentialFilter] = {

    val baseFilters = Seq(
      new CSPFilter(new DefaultCSPResultProcessor(new DefaultCSPProcessor(CSPConfig.fromConfiguration(configuration))))
    )

    val devFilters = Seq(
      new GzipFilter(GzipFilterConfig.fromConfiguration(configuration)),
      new CORSFilterProvider(configuration, httpErrorHandler, new CORSConfigProvider(configuration).get).get
    )

    val filterSeq = Seq(
      true                         -> baseFilters,
      context.devContext.isDefined -> devFilters,
      config.ore.logTimings        -> Seq(new LoggingFilter())
    )

    filterSeq.collect {
      case (true, seq) => seq
    }.flatten
  }

  lazy val routerProvider: Provider[Router] = () => router

  lazy val optionalSourceMapper: OptionalSourceMapper = new OptionalSourceMapper(devContext.map(_.sourceMapper))

  override lazy val httpErrorHandler: HttpErrorHandler =
    new ErrorHandler(wire[OreHttpErrorHandler], wire[JsonHttpErrorHandler])

  lazy val syncCacheApi: SyncCacheApi = new DefaultSyncCacheApi(defaultCacheApi)
  lazy val dbConfigProvider: DatabaseConfigProvider = new DatabaseConfigProvider {
    override def get[P <: BasicProfile]: DatabaseConfig[P] = slickApi.dbConfig(DbName("default"))
  }
  implicit lazy val impMessagesApi: MessagesApi = messagesApi
  implicit lazy val impActorSystem: ActorSystem = actorSystem

  implicit lazy val runtime: Runtime[ZEnv] = Runtime.default

  override lazy val evolutionsReader = new OreEvolutionsReader(environment)

  type ParUIO[A]  = zio.interop.ParIO[Any, Nothing, A]
  type ParTask[A] = zio.interop.ParIO[Any, Throwable, A]

  val taskToUIO: Task ~> UIO = OreComponents.orDieFnK[Any]
  val uioToTask: UIO ~> Task = OreComponents.upcastFnK[UIO, Task]

  implicit lazy val config: OreConfig                              = ConfigSource.fromConfig(configuration.underlying).loadOrThrow[OreConfig]
  implicit lazy val env: OreEnv                                    = wire[OreEnv]
  implicit lazy val markdownRenderer: MarkdownRenderer             = wire[FlexmarkRenderer]
  implicit lazy val fileIORaw: FileIO[ZIO[Blocking, Throwable, *]] = ZIOFileIO(config)

  implicit lazy val fileIO: FileIO[ZIO[Blocking, Nothing, *]] = fileIORaw.imapK(
    OreComponents.orDieFnK[Blocking],
    OreComponents.upcastFnK[ZIO[Blocking, Nothing, *], ZIO[Blocking, Throwable, *]]
  )

  implicit lazy val projectFiles: ProjectFiles[ZIO[Blocking, Nothing, *]] =
    (wire[ProjectFiles.LocalProjectFiles[ZIO[Blocking, Throwable, *]]]: ProjectFiles[ZIO[Blocking, Throwable, *]])
      .mapK(OreComponents.orDieFnK[Blocking])

  implicit val transactor: Transactor[Task] = {
    val cs: ContextShift[Task] = ContextShift[Task]

    applicationResource {
      for {
        connectEC  <- ExecutionContexts.fixedThreadPool[F](32)
        transactEC <- cats.effect.Blocker[F]
      } yield Transactor[F, JdbcDataSource](
        dbConfigProvider.get[JdbcProfile].db.source,
        source => {
          val acquire                = cs.evalOn(connectEC)(F.delay(source.createConnection()))
          def release(c: Connection) = transactEC.blockOn(F.delay(c.close()))
          Resource.make(acquire)(release)
        },
        KleisliInterpreter[F](transactEC).ConnectionInterpreter,
        Strategy.default
      )
    }
  }

  lazy val oreRestfulAPIV1: OreRestfulApiV1                          = wire[OreRestfulServerV1]
  implicit lazy val projectFactory: ProjectFactory                   = wire[OreProjectFactory]
  implicit lazy val modelService: ModelService[UIO]                  = wire[OreModelService].mapK(taskToUIO)
  lazy val emailFactory: EmailFactory                                = wire[EmailFactory]
  lazy val mailer: Mailer                                            = wire[SpongeMailer]
  lazy val projectTask: ProjectTask                                  = wire[ProjectTask]
  lazy val userTask: UserTask                                        = wire[UserTask]
  lazy val dbUpdateTask: DbUpdateTask                                = wire[DbUpdateTask]
  implicit lazy val oreControllerComponents: OreControllerComponents = wire[DefaultOreControllerComponents]
  lazy val uioOreControllerEffects: OreControllerEffects[UIO]        = wire[DefaultOreControllerEffects[UIO]]

  lazy val statTracker: StatTracker[UIO] = (wire[StatTracker.StatTrackerInstant[Task]]: StatTracker[Task])
    .imapK(taskToUIO)(uioToTask)
  lazy val spongeAuthApiTask: SpongeAuthApi[Task] = {
    val api = config.auth.api
    runtime.unsafeRun(
      AkkaSpongeAuthApi[Task](
        AkkaSpongeAuthApi.AkkaSpongeAuthSettings(
          api.key,
          api.url,
          api.breaker.maxFailures,
          api.breaker.reset,
          api.breaker.timeout
        )
      )
    )
  }
  implicit lazy val spongeAuthApi: SpongeAuthApi[UIO] = spongeAuthApiTask.mapK(taskToUIO)
  lazy val ssoApiTask: SSOApi[Task] = {
    val cacher = util.ZIOCacher.instance[Any, Throwable]
    implicit val taskCacher: Cacher[Task] = new Cacher[Task] {
      override def cache[A](duration: FiniteDuration)(fa: Task[A]): Task[Task[A]] =
        cacher.cache(duration)(fa).provide(runtime.environment).map(_.provide(runtime.environment))
    }
    val sso = config.auth.sso
    runtime.unsafeRun(AkkaSSOApi[Task](sso.loginUrl, sso.signupUrl, sso.verifyUrl, sso.secret, sso.timeout, sso.reset))
  }
  lazy val ssoApi: SSOApi[UIO]                   = ssoApiTask.imapK(taskToUIO)(uioToTask)
  implicit lazy val userBaseTask: UserBase[Task] = wire[UserBase.UserBaseF[Task]]
  implicit lazy val userBaseUIO: UserBase[UIO]   = wire[UserBase.UserBaseF[UIO]]
  implicit lazy val projectBase: ProjectBase[UIO] = {
    implicit val providedProjectFiles: ProjectFiles[Task] =
      projectFiles.mapK(OreComponents.provideFnK[Blocking, Nothing](runtime.environment))

    implicit lazy val fileIOTask: FileIO[Task] =
      fileIORaw.imapK(
        new FunctionK[ZIO[Blocking, Throwable, *], Task] {
          def apply[A](fa: ZIO[Blocking, Throwable, A]): Task[A] = fa.provide(runtime.environment)
        },
        OreComponents.upcastFnK[Task, ZIO[Blocking, Throwable, *]]
      )

    // Schrodinger's values, are both used and not used at the same time.
    // Trying to observe if they are will collapse the compile state into an error.
    use(providedProjectFiles)
    use(fileIOTask)

    (wire[ProjectBase.ProjectBaseF[Task]]: ProjectBase[Task]).mapK(taskToUIO)
  }
  implicit lazy val orgBase: OrganizationBase[UIO] =
    (wire[OrganizationBase.OrganizationBaseF[Task]]: OrganizationBase[Task]).mapK(taskToUIO)

  lazy val bakery: Bakery     = wire[Bakery]
  lazy val forms: OreForms    = wire[OreForms]
  lazy val statusZ: StatusZ   = wire[StatusZ]
  lazy val fakeUser: FakeUser = wire[FakeUser]

  lazy val applicationController: Application                          = wire[Application]
  lazy val apiV1Controller: ApiV1Controller                            = wire[ApiV1Controller]
  lazy val apiV2Authentication: apiv2.Authentication                   = wire[apiv2.Authentication]
  lazy val apiV2Keys: apiv2.Keys                                       = wire[apiv2.Keys]
  lazy val apiV2Permissions: apiv2.Permissions                         = wire[apiv2.Permissions]
  lazy val apiV2Projects: apiv2.Projects                               = wire[apiv2.Projects]
  lazy val apiV2Users: apiv2.Users                                     = wire[apiv2.Users]
  lazy val apiV2Versions: apiv2.Versions                               = wire[apiv2.Versions]
  lazy val apiV2Pages: apiv2.Pages                                     = wire[apiv2.Pages]
  lazy val apiV2Organizations: apiv2.Organizations                     = wire[apiv2.Organizations]
  lazy val versions: Versions                                          = wire[Versions]
  lazy val users: Users                                                = wire[Users]
  lazy val projects: Projects                                          = wire[Projects]
  lazy val organizations: Organizations                                = wire[Organizations]
  lazy val reviews: Reviews                                            = wire[Reviews]
  lazy val applicationControllerProvider: Provider[Application]        = () => applicationController
  lazy val apiV1ControllerProvider: Provider[ApiV1Controller]          = () => apiV1Controller
  lazy val apiV2AuthenticationProvider: Provider[apiv2.Authentication] = () => apiV2Authentication
  lazy val apiV2KeysProvider: Provider[apiv2.Keys]                     = () => apiV2Keys
  lazy val apiV2PermissionsProvider: Provider[apiv2.Permissions]       = () => apiV2Permissions
  lazy val apiV2ProjectsProvider: Provider[apiv2.Projects]             = () => apiV2Projects
  lazy val apiV2UsersProvider: Provider[apiv2.Users]                   = () => apiV2Users
  lazy val apiV2VersionsProvider: Provider[apiv2.Versions]             = () => apiV2Versions
  lazy val apiV2PagesProvider: Provider[apiv2.Pages]                   = () => apiV2Pages
  lazy val apiV2OrganizationsProvider: Provider[apiv2.Organizations]   = () => apiV2Organizations
  lazy val versionsProvider: Provider[Versions]                        = () => versions
  lazy val usersProvider: Provider[Users]                              = () => users
  lazy val projectsProvider: Provider[Projects]                        = () => projects
  lazy val organizationsProvider: Provider[Organizations]              = () => organizations
  lazy val reviewsProvider: Provider[Reviews]                          = () => reviews

  def runWhenEvolutionsDone(action: UIO[Unit]): Unit = {
    val isDone    = ZIO.effectTotal(applicationEvolutions.upToDate)
    val waitCheck = Schedule.recurUntilM((_: Unit) => isDone) && Schedule.fixed(Duration.ofMillis(20))

    runtime.unsafeRunAsync(ZIO.unit.repeat(waitCheck).andThen(action)) {
      case Exit.Success(_) => ()
      case Exit.Failure(cause) =>
        logger.error(s"Failed to run action after evolutions done.\n${cause.prettyPrint}")
    }
  }

  runWhenEvolutionsDone(ZIO.effectTotal {
    eager(projectTask)
    eager(userTask)
    eager(dbUpdateTask)
  })

  def eager[A](module: A): Unit = use(module)

  def use[A](@unused value: A): Unit = ()

  def manualRelease[R, E, A](managed: ZManaged[R, E, A]): ZIO[R, E, (A, UIO[Any])] =
    ZManaged.ReleaseMap.make.flatMap { releaseMap =>
      managed.zio.provideSome[R]((_, releaseMap)).map {
        case (_, a) =>
          (a, releaseMap.releaseAll(Exit.unit, ExecutionStrategy.Sequential))
      }
    }

  def applicationResource[A](resource: Resource[Task, A]): A = {
    val (a, finalize) = runtime.unsafeRunSync(resource.allocated).toEither.toTry.get

    applicationLifecycle.addStopHook(() => runtime.unsafeRunToFuture(finalize))

    a
  }

  def applicationManaged[A](managed: ZManaged[ZEnv, Throwable, A]): A = {
    managed.preallocate

    val (a, finalize) = runtime.unsafeRunSync(manualRelease(managed)).toEither.toTry.get

    applicationLifecycle.addStopHook(() => runtime.unsafeRunToFuture(finalize))

    a
  }
}
object OreComponents {
  //macwire doesn't seem to like this function
  def upcastFnK[From[_], To[A] >: From[A]]: From ~> To = new FunctionK[From, To] {
    override def apply[A](fa: From[A]): To[A] = fa
  }
  def provideFnK[R, E](environment: R): ZIO[R, E, *] ~> ZIO[Any, E, *] = new FunctionK[ZIO[R, E, *], ZIO[Any, E, *]] {
    override def apply[A](fa: ZIO[R, E, A]): ZIO[Any, E, A] = fa.provide(environment)
  }
  def orDieFnK[R]: ZIO[R, Throwable, *] ~> ZIO[R, Nothing, *] =
    new FunctionK[ZIO[R, Throwable, *], ZIO[R, Nothing, *]] {
      override def apply[A](fa: ZIO[R, Throwable, A]): ZIO[R, Nothing, A] = fa.orDie
    }
}
