package models.protocols

import java.time.OffsetDateTime

import ore.data.project.Category
import ore.models.project.Version.{ReleaseType, Stability}
import ore.models.project.{ReviewState, Visibility}
import ore.permission.NamedPermission

import enumeratum._
import enumeratum.values._
import io.circe._
import io.circe.derivation.annotations.SnakeCaseJsonCodec
import io.circe.syntax._
import shapeless.Typeable

object APIV2 {

  def optionToResult[A](s: String, opt: String => Option[A], history: List[CursorOp])(
      implicit tpe: Typeable[A]
  ): Either[DecodingFailure, A] =
    opt(s).toRight(DecodingFailure(s"$s is not a valid ${tpe.describe}", history))

  def valueEnumCodec[V, A <: ValueEnumEntry[V]: Typeable](enumObj: ValueEnum[V, A])(name: A => String): Codec[A] =
    Codec.from(
      (c: HCursor) => c.as[String].flatMap(optionToResult(_, s => enumObj.values.find(a => name(a) == s), c.history)),
      (a: A) => name(a).asJson
    )

  def enumCodec[A <: EnumEntry: Typeable](enumObj: Enum[A])(name: A => String): Codec[A] = Codec.from(
    (c: HCursor) => c.as[String].flatMap(optionToResult(_, s => enumObj.values.find(a => name(a) == s), c.history)),
    (a: A) => name(a).asJson
  )

  implicit val visibilityCodec: Codec[Visibility]   = valueEnumCodec(Visibility)(_.nameKey)
  implicit val categoryCodec: Codec[Category]       = valueEnumCodec(Category)(_.apiName)
  implicit val reviewStateCodec: Codec[ReviewState] = valueEnumCodec(ReviewState)(_.apiName)

  implicit val namedPermissionCodec: Codec[NamedPermission] = APIV2.enumCodec(NamedPermission)(_.entryName)

  implicit val permissionRoleCodec: Codec[ore.permission.role.Role] = valueEnumCodec(ore.permission.role.Role)(_.value)

  //Project
  @SnakeCaseJsonCodec case class Project(
      createdAt: OffsetDateTime,
      pluginId: String,
      name: String,
      namespace: ProjectNamespace,
      promotedVersions: Seq[PromotedVersion],
      stats: ProjectStatsAll,
      category: Category,
      summary: Option[String],
      lastUpdated: OffsetDateTime,
      visibility: Visibility,
      userActions: UserActions,
      settings: ProjectSettings,
      iconUrl: String,
      external: ProjectExternal
  )

  @SnakeCaseJsonCodec case class ProjectExternal(
      discourse: ProjectExternalDiscourse
  )

  @SnakeCaseJsonCodec case class ProjectExternalDiscourse(
      topicId: Option[Int],
      postId: Option[Int]
  )

  @SnakeCaseJsonCodec case class CompactProject(
      pluginId: String,
      name: String,
      namespace: ProjectNamespace,
      promotedVersions: Seq[PromotedVersion],
      stats: ProjectStatsAll,
      category: Category,
      visibility: Visibility
  )

  @SnakeCaseJsonCodec case class ProjectNamespace(owner: String, slug: String)
  @SnakeCaseJsonCodec case class PromotedVersion(version: String, platforms: Seq[VersionPlatform])
  @SnakeCaseJsonCodec case class ProjectStatsAll(
      views: Long,
      downloads: Long,
      recentViews: Long,
      recentDownloads: Long,
      stars: Long,
      watchers: Long
  )
  @SnakeCaseJsonCodec case class UserActions(starred: Boolean, watching: Boolean)
  @SnakeCaseJsonCodec case class ProjectSettings(
      keywords: Seq[String],
      homepage: Option[String],
      issues: Option[String],
      sources: Option[String],
      support: Option[String],
      license: ProjectLicense,
      forumSync: Boolean
  )
  @SnakeCaseJsonCodec case class ProjectLicense(name: Option[String], url: Option[String])

  @SnakeCaseJsonCodec case class Member(
      user: String,
      role: Role
  )

  @SnakeCaseJsonCodec case class Membership(
      scope: String,
      organization: Option[MembershipOrganization],
      project: Option[MembershipProject],
      role: ore.permission.role.Role,
      isAccepted: Boolean
  )

  @SnakeCaseJsonCodec case class MembershipOrganization(
      name: String
  )

  @SnakeCaseJsonCodec case class MembershipProject(
      pluginId: String,
      namespace: ProjectNamespace
  )

  @SnakeCaseJsonCodec case class Role(
      name: ore.permission.role.Role,
      title: String,
      color: String,
      permissions: List[NamedPermission],
      isAccepted: Boolean
  )

  //Version
  @SnakeCaseJsonCodec case class Version(
      createdAt: OffsetDateTime,
      name: String,
      dependencies: List[VersionDependency],
      visibility: Visibility,
      stats: VersionStatsAll,
      fileInfo: FileInfo,
      author: Option[String],
      reviewState: ReviewState,
      tags: VersionTags,
      external: VersionExternal
  )

  @SnakeCaseJsonCodec case class VersionExternal(
      discourse: VersionExternalDiscourse
  )

  @SnakeCaseJsonCodec case class VersionExternalDiscourse(
      postId: Option[Int]
  )

  @SnakeCaseJsonCodec case class VersionTags(
      mixin: Boolean,
      stability: Stability,
      releaseType: Option[ReleaseType],
      platforms: Seq[VersionPlatform]
  )

  @SnakeCaseJsonCodec case class VersionPlatform(
      platform: String,
      platformVersion: Option[String],
      displayPlatformVersion: Option[String],
      minecraftVersion: Option[String]
  )

  @SnakeCaseJsonCodec case class VersionChangelog(changelog: String)

  @SnakeCaseJsonCodec case class VersionDependency(pluginId: String, version: Option[String])
  @SnakeCaseJsonCodec case class VersionStatsAll(downloads: Long)
  @SnakeCaseJsonCodec case class FileInfo(name: String, sizeBytes: Long, md5Hash: String)

  //User
  @SnakeCaseJsonCodec case class User(
      createdAt: OffsetDateTime,
      name: String,
      tagline: Option[String],
      joinDate: Option[OffsetDateTime],
      projectCount: Long,
      roles: List[Role]
  )

  @SnakeCaseJsonCodec case class Organization(
      owner: String,
      user: User
  )

  @SnakeCaseJsonCodec case class ProjectStatsDay(
      downloads: Long,
      views: Long
  )

  @SnakeCaseJsonCodec case class VersionStatsDay(
      downloads: Long
  )

  @SnakeCaseJsonCodec case class Page(
      name: String,
      content: Option[String]
  )

  @SnakeCaseJsonCodec case class PageList(
      pages: Seq[PageListEntry]
  )

  @SnakeCaseJsonCodec case class PageListEntry(
      name: Seq[String],
      slug: Seq[String],
      navigational: Boolean
  )
}
