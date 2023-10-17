package ore.db.impl.schema

import java.time.OffsetDateTime

import scala.reflect.ClassTag

import ore.db.{DbRef, impl}
import ore.db.impl.OrePostgresDriver
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.table.common.{DescriptionColumn, VisibilityColumn}
import ore.models.project.{Project, ReviewState, TagColor, Version}
import ore.models.user.User

//noinspection MutatorLikeMethodIsParameterless
class VersionTable(tag: Tag)
    extends ModelTable[Version](tag, "project_versions")
    with DescriptionColumn[Version]
    with VisibilityColumn[Version] {

  implicit private val listOptionStrType: OrePostgresDriver.DriverJdbcType[List[Option[String]]] =
    new OrePostgresDriver.SimpleArrayJdbcType[String]("text")
      .to[List](
        //DANGER
        _.map(Option(_)).toList.asInstanceOf[List[String]],
        _.asInstanceOf[List[Option[String]]].map(_.orNull)
      )
      .asInstanceOf[OrePostgresDriver.DriverJdbcType[List[Option[String]]]]

  def versionString      = column[String]("version_string")
  def dependencyIds      = column[List[String]]("dependency_ids")
  def dependencyVersions = column[List[Option[String]]]("dependency_versions")
  def projectId          = column[DbRef[Project]]("project_id")
  def fileSize           = column[Long]("file_size")
  def hash               = column[String]("hash")
  def authorId           = column[DbRef[User]]("author_id")
  def reviewStatus       = column[ReviewState]("review_state")
  def reviewerId         = column[DbRef[User]]("reviewer_id")
  def approvedAt         = column[OffsetDateTime]("approved_at")
  def fileName           = column[String]("file_name")
  def createForumPost    = column[Boolean]("create_forum_post")
  def postId             = column[Option[Int]]("post_id")

  def usesMixin    = column[Boolean]("uses_mixin")
  def stability    = column[Version.Stability]("stability")
  def releaseType  = column[Version.ReleaseType]("release_type")
  def channelName  = column[String]("legacy_channel_name")
  def channelColor = column[TagColor]("legacy_channel_color")

  def tags =
    (
      usesMixin,
      stability,
      releaseType.?,
      channelName.?,
      channelColor.?
    ).<>(Version.VersionTags.tupled, Version.VersionTags.unapply)

  override def * =
    (
      id.?,
      createdAt.?,
      (
        projectId,
        versionString,
        dependencyIds,
        dependencyVersions,
        fileSize,
        hash,
        authorId.?,
        description.?,
        reviewStatus,
        reviewerId.?,
        approvedAt.?,
        visibility,
        fileName,
        createForumPost,
        postId,
        tags
      )
    ).<>(mkApply((Version.apply _).tupled), mkUnapply(Version.unapply))
}
