package db.impl.query

import ore.db.DbRef
import ore.models.project.Project

import doobie.implicits._

object SharedQueries {

  val refreshHomeView: doobie.Update0 = sql"REFRESH MATERIALIZED VIEW home_projects".update

  def watcherStartProject(id: DbRef[Project]): doobie.Query0[Long] =
    sql"""SELECT p.stars FROM project_stats p WHERE p.id = $id""".query[Long]
}
