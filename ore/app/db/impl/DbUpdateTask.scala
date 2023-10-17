package db.impl

import play.api.inject.ApplicationLifecycle

import db.impl.access.ProjectBase
import db.impl.query.StatTrackerQueries
import ore.OreConfig
import ore.db.ModelService
import ore.util.OreMDC

import cats.syntax.all._
import com.typesafe.scalalogging
import doobie.implicits._
import zio.clock.Clock
import zio._

class DbUpdateTask(config: OreConfig, lifecycle: ApplicationLifecycle, runtime: zio.Runtime[Clock])(
    implicit service: ModelService[Task]
) {

  val interval: duration.Duration = duration.Duration.fromScala(config.ore.homepage.updateInterval)

  private val Logger               = scalalogging.Logger.takingImplicit[OreMDC]("DbUpdateTask")
  implicit private val mdc: OreMDC = OreMDC.NoMDC

  Logger.info("DbUpdateTask starting")

  private val materializedViewsSchedule: Schedule[Any, Unit, Unit] =
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

  private val materializedViewsTask = runningTask(
    service.runDbCon(
      sql"SELECT refreshProjectStats()"
        .query[Option[Int]]
        .unique *> sql"REFRESH MATERIALIZED VIEW promoted_versions".update.run.void
    ),
    materializedViewsSchedule
  )

  private def runMany(updates: Seq[doobie.Update0]) =
    service.runDbCon(updates.toList.traverse_(_.run))

  private val statsTask = runningTask(
    runMany(StatTrackerQueries.processProjectViews) *>
      runMany(StatTrackerQueries.processVersionDownloads),
    statSchedule
  )

  lifecycle.addStopHook(() => materializedViewsTask.cancel())
  lifecycle.addStopHook(() => statsTask.cancel())
}
