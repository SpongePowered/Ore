package ore

import java.time.OffsetDateTime

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{ProjectTable, VersionTable}
import ore.db.{Model, ObjId}
import ore.discourse.DiscourseError
import ore.models.project.{Project, Version}
import ore.models.{Job, JobInfo}

import akka.pattern.CircuitBreakerOpenException
import com.typesafe.scalalogging
import cats.syntax.all._
import slick.lifted.TableQuery
import zio._
import zio.clock.Clock

object JobsProcessor {
  private val Logger = scalalogging.Logger("JobsProcessor")

  def fiber: URIO[Db with Clock with Discourse with Config, Unit] =
    tryGetJob.flatMap {
      case Some(job) =>
        (logJob(job) *> decodeJob(job).mapError(Right.apply) >>= processJob >>= finishJob).catchAll(logErrors) *> fiber
      case None =>
        ZIO.descriptorWith(desc => UIO(Logger.debug(s"No more jobs found. Finishing fiber ${desc.id}"))): UIO[Unit]
    }

  private def logErrors(e: Either[Unit, String]): UIO[Unit] = {
    e.foreach(s => Logger.error(s))
    ZIO.succeed(())
  }

  private def tryGetJob: URIO[Db, Option[Model[Job]]] =
    ZIO.accessM(_.get.runDbCon(JobsQueries.tryGetjob.option))

  private def logJob(job: Model[Job]): UIO[Unit] =
    ZIO.descriptorWith(desc => UIO(Logger.debug(s"Starting on fiber ${desc.id} job $job")))

  private def updateJobInfo(job: Job)(update: JobInfo => JobInfo): Job =
    job.copy(info = update(job.info).copy(lastUpdated = Some(OffsetDateTime.now())))

  private def asFatalFailure(job: Job, error: String, errorDescriptor: String): Job = {
    updateJobInfo(job)(
      _.copy(
        lastUpdated = Some(OffsetDateTime.now()),
        lastError = Some(error),
        lastErrorDescriptor = Some(errorDescriptor),
        state = Job.JobState.FatalFailure
      )
    )
  }

  private def decodeJob(job: Model[Job]): ZIO[Db, String, Model[Job.TypedJob]] = {
    job.toTyped.map(r => Model[Job.TypedJob](ObjId(job.id.value), job.createdAt, r)) match {
      case Left(e) =>
        ZIO
          .accessM[Db](_.get.update(job)(asFatalFailure(_, e, "decode_failed")))
          .andThen(ZIO.fail(s"Failed to decode job\nJob: $job\nError: $e"))

      case Right(typedJob) => ZIO.succeed(typedJob)
    }
  }

  private def convertJob[A, B](typed: Model[Job.TypedJob], ctx: A)(f: (A, Model[Job]) => B): B =
    f(ctx, Model(ObjId(typed.id), typed.createdAt, typed.toJob))

  private def updateJob(job: Model[Job.TypedJob], update: JobInfo => JobInfo): URIO[Db, Model[Job]] =
    ZIO.accessM[Db](convertJob(job, _)(_.get.update(_)(updateJobInfo(_)(update))))

  private def setLastUpdated(job: Model[Job.TypedJob]): URIO[Db, Unit] =
    updateJob(job, identity).unit

  private def finishJob(job: Model[Job.TypedJob]): URIO[Db, Unit] =
    updateJob(job, _.copy(state = Job.JobState.Done)).unit

  private def getDataOrFatal[A](
      data: UIO[Option[A]],
      job: Model[Job.TypedJob],
      error: => String,
      errorDescriptor: String
  ): ZIO[Db, Either[Unit, String], A] = data.flatMap {
    case Some(value) => ZIO.succeed(value)
    case None =>
      ZIO
        .accessM[Db](convertJob(job, _)(_.get.update(_)(asFatalFailure(_, error, errorDescriptor))))
        .andThen(ZIO.fail(Right(error)))
  }

  private def processJob(
      job: Model[Job.TypedJob]
  ): ZIO[Db with Discourse with Config, Either[Unit, String], Model[Job.TypedJob]] =
    setLastUpdated(job) *> ZIO.access[Db](_.get).flatMap { implicit service =>
      job.obj match {
        case Job.UpdateDiscourseProjectTopic(_, projectId) =>
          getDataOrFatal(
            ModelView.now(Project).get(projectId).value,
            job,
            s"Can't find project with id $projectId",
            "project_not_found"
          ).flatMap(updateProjectTopic(job, _))

        case Job.UpdateDiscourseVersionPost(_, versionId) =>
          val q = for {
            v <- TableQuery[VersionTable]
            p <- TableQuery[ProjectTable]
            if v.id === versionId && v.projectId === p.id
          } yield (v, p)

          getDataOrFatal(
            service.runDBIO(q.result.headOption),
            job,
            s"Can't find version with id $versionId",
            "version_not_found"
          ).flatMap {
            case (version, project) =>
              updateVersionPost(job, project, version)
          }

        case Job.DeleteDiscourseTopic(_, topicId) =>
          deleteTopic(job, topicId)

        case Job.PostDiscourseReply(_, topicId, poster, content) =>
          postReply(job, topicId, poster, content)
      }
    }

  def handleDiscourseErrors(job: Model[Job.TypedJob])(
      program: Discourse => ZIO[Any, Throwable, Either[DiscourseError, Unit]]
  ): ZIO[Db with Discourse with Config, Either[Unit, String], Model[Job.TypedJob]] = {
    def retryIn(error: Option[String], errorDescriptor: Option[String])(duration: FiniteDuration) =
      updateJob(
        job,
        _.copy(
          retryAt = Some(OffsetDateTime.now().plusNanos((duration + 5.seconds).toNanos)),
          lastError = error.orElse(job.info.lastError),
          lastErrorDescriptor = errorDescriptor.orElse(job.info.lastErrorDescriptor),
          state = Job.JobState.NotStarted
        )
      ).andThen(ZIO.fail(error.toRight(())))

    def retryInConfig(error: Option[String], errorDescriptor: Option[String])(
        duration: OreJobsConfig => FiniteDuration
    ) =
      ZIO.access[Config](env => duration(env.get)) >>= retryIn(error, errorDescriptor)

    ZIO
      .accessM[Discourse](program)
      .catchSome {
        case _: TimeoutException =>
          ZIO.succeed(Left(()))

        case e: CircuitBreakerOpenException => retryIn(None, None)(e.remainingDuration).catchAll(ZIO.succeed(_))
      }
      .orDie
      .absolve
      .as(job)
      .catchAll {
        case DiscourseError.RatelimitError(waitTime) => retryIn(None, None)(waitTime)

        case DiscourseError.UnknownError(messages, tpe, extras) =>
          val e = s"""|Encountered error when executing Discourse request
                      |Job: ${job.obj.withoutError}
                      |Type: $tpe
                      |Messages: ${messages.mkString("\n  ", "\n  ", "")}
                      |Extras: ${extras.mkString("\n  ", "\n  ", "")}""".stripMargin

          retryInConfig(Some(e), Some(s"unknown_error_$tpe"))(_.jobs.timeouts.unknownError)
        case DiscourseError.StatusError(statusCode, message) =>
          val e = s"""|Encountered status error when executing Discourse request
                      |Job: ${job.obj.withoutError}
                      |Status code: $statusCode
                      |Message: ${message.getOrElse("None")}""".stripMargin

          retryInConfig(Some(e), Some(s"status_error_${statusCode.intValue}"))(_.jobs.timeouts.statusError)
        case DiscourseError.NotAvailable => retryInConfig(None, None)(_.jobs.timeouts.notAvailable)
      }
  }

  private def updateProjectTopic(job: Model[Job.TypedJob], project: Model[Project]) =
    handleDiscourseErrors(job) { env =>
      if (project.topicId.isDefined) env.get.updateProjectTopic(project)
      else env.get.createProjectTopic(project).map(_.void)
    }

  private def updateVersionPost(job: Model[Job.TypedJob], project: Model[Project], version: Model[Version]) = {
    handleDiscourseErrors(job) { env =>
      val doVersion = (project: Model[Project]) =>
        if (version.postId.isDefined) env.get.updateVersionPost(project, version)
        else env.get.createVersionPost(project, version).map(_.void)

      if (project.topicId.isDefined) {
        doVersion(project)
      } else {
        env.get.createProjectTopic(project).flatMap {
          case Left(e)      => ZIO.succeed(Left(e))
          case Right(value) => doVersion(value)
        }
      }
    }
  }

  private def deleteTopic(job: Model[Job.TypedJob], topicId: Int) =
    handleDiscourseErrors(job)(_.get.deleteTopic(topicId))

  private def postReply(job: Model[Job.TypedJob], topicId: Int, poster: String, content: String) =
    handleDiscourseErrors(job)(_.get.postDiscussionReply(topicId, poster, content).map(_.void))

}
