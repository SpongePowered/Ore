package ore.db.impl.schema

import ore.db.DbRef
import ore.db.impl.OrePostgresDriver.api._
import ore.models.project.{Version, VersionPlatform}

class VersionPlatformTable(tag: Tag) extends ModelTable[VersionPlatform](tag, "project_version_platforms") {

  def versionId             = column[DbRef[Version]]("version_id")
  def platform              = column[String]("platform")
  def platformVersion       = column[Option[String]]("platform_version")
  def platformCoarseVersion = column[Option[String]]("platform_coarse_version")

  override def * =
    (id.?, createdAt.?, (versionId, platform, platformVersion, platformCoarseVersion)).<>(
      mkApply(
        (VersionPlatform.apply _).tupled
      ),
      mkUnapply(VersionPlatform.unapply)
    )
}
