package db.impl

import play.api.inject.ApplicationLifecycle

import db.impl.access.ProjectBase
import db.impl.query.StatTrackerQueries
import ore.OreConfig
import ore.db.ModelService
import ore.util.OreMDC

import cats.syntax.all._
import com.typesafe.scalalogging
import zio.clock.Clock
import zio._

class DbUpdateTask(config: OreConfig, lifecycle: ApplicationLifecycle, runtime: zio.Runtime[Clock])(
    implicit projects: ProjectBase[Task],
    service: ModelService[Task]
) {

  val interval: duration.Duration = duration.Duration.fromScala(config.ore.homepage.updateInterval)

  private val Logger               = scalalogging.Logger.takingImplicit[OreMDC]("DbUpdateTask")
  implicit private val mdc: OreMDC = OreMDC.NoMDC

  Logger.info("DbUpdateTask starting")

  private val homepageSchedule: Schedule[Any, Unit, Unit] =
    Schedule
      .fixed(interval)
      .unit
      .tapInput((_: Unit) => UIO(Logger.debug(s"Updating homepage view")))

  private val statSchedule: Schedule[Any, Unit, Unit] =
    Schedule
      .fixed(interval)
      .unit
      .tapInput((_: Unit) => UIO(Logger.debug("Processing stats")))

  private def runningTask(task: RIO[Clock, Unit], schedule: Schedule[Any, Unit, Unit]) = {
    val safeTask: ZIO[Clock, Nothing, Unit] = task.catchAll(e => UIO(Logger.error("Running DB task failed", e)))

    runtime.unsafeRunToFuture(safeTask.repeat(schedule))
  }

  private val homepageTask = runningTask(projects.refreshHomePage(Logger), homepageSchedule)

  private def runMany(updates: Seq[doobie.Update0]) =
    service.runDbCon(updates.toList.traverse_(_.run))

  private val statsTask = runningTask(
    runMany(StatTrackerQueries.processProjectViews) *>
      runMany(StatTrackerQueries.processVersionDownloads),
    statSchedule
  )

  lifecycle.addStopHook(() => homepageTask.cancel())
  lifecycle.addStopHook(() => statsTask.cancel())
}
