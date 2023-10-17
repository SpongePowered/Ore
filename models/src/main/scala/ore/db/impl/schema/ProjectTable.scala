package ore.db.impl.schema

import ore.data.project.Category
import ore.db.DbRef
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.table.common.{DescriptionColumn, NameColumn, VisibilityColumn}
import ore.models.project._
import ore.models.user.User

import io.circe.Json

class ProjectTable(tag: Tag)
    extends ModelTable[Project](tag, "projects")
    with NameColumn[Project]
    with VisibilityColumn[Project]
    with DescriptionColumn[Project] {

  def pluginId    = column[String]("plugin_id")
  def ownerName   = column[String]("owner_name")
  def ownerId     = column[DbRef[User]]("owner_id")
  def slug        = column[String]("slug")
  def category    = column[Category]("category")
  def topicId     = column[Option[Int]]("topic_id")
  def postId      = column[Int]("post_id")
  def notes       = column[Json]("notes")
  def keywords    = column[List[String]]("keywords")
  def homepage    = column[String]("homepage")
  def issues      = column[String]("issues")
  def source      = column[String]("source")
  def support     = column[String]("support")
  def licenseName = column[String]("license_name")
  def licenseUrl  = column[String]("license_url")
  def forumSync   = column[Boolean]("forum_sync")

  def settings =
    (
      keywords,
      homepage.?,
      issues.?,
      source.?,
      support.?,
      licenseName.?,
      licenseUrl.?,
      forumSync
    ).<>(Project.ProjectSettings.tupled, Project.ProjectSettings.unapply)

  override def * =
    (
      id.?,
      createdAt.?,
      (
        pluginId,
        ownerName,
        ownerId,
        name,
        category,
        description.?,
        topicId,
        postId.?,
        visibility,
        notes,
        settings
      )
    ).<>(mkApply((Project.apply _).tupled), mkUnapply(Project.unapply))
}
