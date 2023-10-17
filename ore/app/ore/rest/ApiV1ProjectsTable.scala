package ore.rest

import ore.db.DbRef
import ore.db.impl.OrePostgresDriver.api._
import ore.models.project.Project

import io.circe.Json

class ApiV1ProjectsTable(tag: Tag) extends Table[ApiV1Project](tag, "apiv1_projects") {

  def id               = column[DbRef[Project]]("id")
  def promotedVersions = column[Json]("promoted_versions")

  override def * = (id, promotedVersions).<>((ApiV1Project.apply _).tupled, ApiV1Project.unapply)
}

case class ApiV1Project(id: DbRef[Project], promotedVersions: Json)
