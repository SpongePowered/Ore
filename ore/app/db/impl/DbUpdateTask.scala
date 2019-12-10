package db.impl

import javax.inject.{Inject, Singleton}

import scala.concurrent.{ExecutionContext, Future}

import play.api.inject.ApplicationLifecycle

import db.impl.access.ProjectBase
import db.impl.query.StatTrackerQueries
import ore.OreConfig
import ore.db.ModelService
import ore.util.OreMDC

import cats.syntax.all._
import com.typesafe.scalalogging
import doobie.`enum`.TransactionIsolation
import zio.clock.Clock
import zio.{RIO, Task, UIO, ZIO, ZSchedule, duration}

@Singleton
class DbUpdateTask @Inject()(config: OreConfig, lifecycle: ApplicationLifecycle, runtime: zio.Runtime[Clock])(
    implicit ec: ExecutionContext,
    projects: ProjectBase[Task],
    service: ModelService[Task]
) {

  val interval: duration.Duration = duration.Duration.fromScala(config.ore.homepage.updateInterval)

  private val Logger               = scalalogging.Logger.takingImplicit[OreMDC]("DbUpdateTask")
  implicit private val mdc: OreMDC = OreMDC.NoMDC

  Logger.info("DbUpdateTask starting")

  private val homepageSchedule: ZSchedule[Clock, Any, Int] = ZSchedule
    .fixed(interval)
    .logInput(_ => UIO(Logger.debug(s"Updating homepage view")))

  private val statSchedule: ZSchedule[Clock, Any, Int] =
    ZSchedule
      .fixed(interval)
      .logInput(_ => UIO(Logger.debug("Processing stats")))

  private def runningTask(task: RIO[Clock, Unit], schedule: ZSchedule[Clock, Any, Int]) = {
    val safeTask: ZIO[Clock, Unit, Unit] = task.flatMapError(e => UIO(Logger.error("Running DB task failed", e)))

    runtime.unsafeRun(safeTask.repeat(schedule).fork)
  }

  private val homepageTask = runningTask(projects.refreshHomePage(Logger), homepageSchedule)

  private def runManyInTransaction(updates: Seq[doobie.Update0]) = {
    import cats.instances.list._
    import doobie._

    service
      .runDbCon(
        for {
          _ <- HC.setTransactionIsolation(TransactionIsolation.TransactionRepeatableRead)
          _ <- updates.toList.traverse_(_.run)
        } yield ()
      )
      .retry(ZSchedule.forever)
  }

  private val statsTask = runningTask(
    runManyInTransaction(StatTrackerQueries.processProjectViews) *>
      runManyInTransaction(StatTrackerQueries.processVersionDownloads),
    statSchedule
  )
  lifecycle.addStopHook { () =>
    Future {
      runtime.unsafeRun(homepageTask.interrupt)
      runtime.unsafeRun(statsTask.interrupt)
    }
  }
}
