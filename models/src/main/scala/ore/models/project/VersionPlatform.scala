package ore.models.project

import ore.db.impl.DefaultModelCompanion
import ore.db.impl.schema.VersionPlatformTable
import ore.db.{DbRef, ModelQuery}

import slick.lifted.TableQuery

case class VersionPlatform(
    versionId: DbRef[Version],
    platform: String,
    platformVersion: Option[String],
    platformCoarseVersion: Option[String]
)
object VersionPlatform
    extends DefaultModelCompanion[VersionPlatform, VersionPlatformTable](TableQuery[VersionPlatformTable]) {

  implicit val query: ModelQuery[VersionPlatform] = ModelQuery.from(this)
}
