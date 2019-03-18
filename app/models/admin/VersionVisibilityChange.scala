package models.admin

import java.sql.Timestamp

import play.twirl.api.Html

import db.impl.model.common.VisibilityChange
import db.impl.schema.VersionVisibilityChangeTable
import db.{DbRef, DefaultModelCompanion, ModelQuery}
import models.project.{Page, Version, Visibility}
import models.user.User
import ore.OreConfig

import slick.lifted.TableQuery

case class VersionVisibilityChange(
    createdBy: Option[DbRef[User]],
    versionId: DbRef[Version],
    comment: String,
    resolvedAt: Option[Timestamp],
    resolvedBy: Option[DbRef[User]],
    visibility: Visibility
) extends VisibilityChange {

  /** Render the comment as Html */
  def renderComment(implicit config: OreConfig): Html = Page.render(comment)
}
object VersionVisibilityChange
    extends DefaultModelCompanion[VersionVisibilityChange, VersionVisibilityChangeTable](
      TableQuery[VersionVisibilityChangeTable]
    ) {

  implicit val query: ModelQuery[VersionVisibilityChange] =
    ModelQuery.from(this)
}
