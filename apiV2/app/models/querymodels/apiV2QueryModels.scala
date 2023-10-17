package models.querymodels

import java.time.{LocalDate, OffsetDateTime}

import scala.collection.immutable.TreeMap
import scala.jdk.CollectionConverters._
import scala.util.Try

import play.api.mvc.RequestHeader

import models.protocols.APIV2
import ore.OreConfig
import ore.data.project.{Category, ProjectNamespace}
import ore.models.project.Version.{ReleaseType, Stability}
import ore.models.project.io.ProjectFiles
import ore.models.project.{ReviewState, Visibility}
import ore.models.user.User
import ore.permission.role.Role
import util.syntax._

import cats.kernel.Order
import cats.syntax.all._
import io.circe.{DecodingFailure, Json}
import org.spongepowered.plugin.meta.version.{ArtifactVersion, DefaultArtifactVersion, VersionRange}
import zio.ZIO
import zio.blocking.Blocking

case class APIV2QueryProject(
    createdAt: OffsetDateTime,
    pluginId: String,
    name: String,
    namespace: ProjectNamespace,
    promotedVersions: Json,
    views: Long,
    downloads: Long,
    recentViews: Long,
    recentDownloads: Long,
    stars: Long,
    watchers: Long,
    category: Category,
    description: Option[String],
    lastUpdated: OffsetDateTime,
    visibility: Visibility,
    topicId: Option[Int],
    postId: Option[Int],
    userStarred: Boolean,
    userWatching: Boolean,
    keywords: List[String],
    homepage: Option[String],
    issues: Option[String],
    sources: Option[String],
    support: Option[String],
    licenseName: Option[String],
    licenseUrl: Option[String],
    forumSync: Boolean
) {

  def asProtocol(
      implicit projectFiles: ProjectFiles[ZIO[Blocking, Nothing, *]],
      requestHeader: RequestHeader,
      config: OreConfig
  ): ZIO[Blocking, Nothing, APIV2.Project] = {
    val iconPath = projectFiles.getIconPath(namespace.ownerName, name)
    val iconUrlF = iconPath.map(_.isDefined).map {
      case true  => controllers.project.routes.Projects.showIcon(namespace.ownerName, namespace.slug).absoluteURL()
      case false => User.avatarUrl(namespace.ownerName)
    }

    for {
      promotedVersionDecoded <- ZIO.fromEither(APIV2QueryProject.decodePromotedVersions(promotedVersions)).orDie
      iconUrl                <- iconUrlF
    } yield {
      APIV2.Project(
        createdAt,
        pluginId,
        name,
        APIV2.ProjectNamespace(
          namespace.ownerName,
          namespace.slug
        ),
        promotedVersionDecoded,
        APIV2.ProjectStatsAll(
          views,
          downloads,
          recentViews,
          recentDownloads,
          stars,
          watchers
        ),
        category,
        description,
        lastUpdated,
        visibility,
        APIV2.UserActions(
          userStarred,
          userWatching
        ),
        APIV2.ProjectSettings(
          keywords,
          homepage,
          issues,
          sources,
          support,
          APIV2.ProjectLicense(licenseName, licenseUrl),
          forumSync
        ),
        iconUrl,
        APIV2.ProjectExternal(
          APIV2.ProjectExternalDiscourse(
            topicId,
            postId
          )
        )
      )
    }
  }
}
object APIV2QueryProject {
  private val MajorMinor                = """(\d+\.\d+)(?:\.\d+)?(?>-SNAPSHOT)?(?>-[a-z0-9]{7,9})?""".r
  private val SpongeForgeMajorMinorMC   = """(\d+\.\d+)\.\d+-\d+-(\d+\.\d+\.\d+)(?:(?:-BETA-\d+)|(?:-RC\d+))?""".r
  private val SpongeVanillaMajorMinorMC = """(\d+\.\d+)\.\d+-(\d+\.\d+\.\d+)(?:(?:-BETA-\d+)|(?:-RC\d+))?""".r
  private val OldForgeVersion           = """\d+\.(\d+\.\d+\.\d+)""".r

  implicit private val artifactVersionOrder: Order[ArtifactVersion] = Order.fromComparable[ArtifactVersion]

  def decodePromotedVersions(promotedVersions: Json): Either[DecodingFailure, Vector[APIV2.PromotedVersion]] =
    for {
      jsons <- promotedVersions.hcursor.values.toRight(DecodingFailure("Invalid promoted versions", Nil))
      res <- jsons.toVector.traverse { json =>
        val cursor = json.hcursor

        for {
          version         <- cursor.get[String]("version_string")
          platform        <- cursor.get[List[String]]("platforms")
          platformVersion <- cursor.get[List[Option[String]]]("platform_versions")
        } yield APIV2.PromotedVersion(
          version,
          platform.zip(platformVersion).map {
            case (platform, platformVersion) => decodeVersionPlatform(platform, platformVersion)
          }
        )
      }
    } yield res

  def decodeVersionPlatform(platform: String, platformVersion: Option[String]): APIV2.VersionPlatform = {
    //TODO: Cleanup this in the DB so we don't need to deal with it
    val displayAndMc = platformVersion.filterNot(_ == "null").map { rawData =>
      lazy val lowerBoundVersion = for {
        range <- Try(VersionRange.createFromVersionSpec(rawData)).toOption
        version <- Option(range.getRecommendedVersion)
          .orElse(range.getRestrictions.asScala.flatMap(r => Option(r.getLowerBound)).toVector.minimumOption)
      } yield version

      lazy val lowerBoundVersionStr = lowerBoundVersion.map(_.toString)

      def unzipOptions[A, B](fab: Option[(A, B)]): (Option[A], Option[B]) = fab match {
        case Some((a, b)) => Some(a) -> Some(b)
        case None         => (None, None)
      }

      //TODO: Move this to the platforms object
      platform match {
        case "spongeapi" =>
          lowerBoundVersionStr.collect {
            case MajorMinor(version) => version
          } -> None //TODO
        case "spongeforge" =>
          unzipOptions(
            lowerBoundVersionStr.collect {
              case SpongeForgeMajorMinorMC(version, mcVersion) => version -> mcVersion
            }
          )
        case "spongevanilla" =>
          unzipOptions(
            lowerBoundVersionStr.collect {
              case SpongeVanillaMajorMinorMC(version, mcVersion) => version -> mcVersion
            }
          )
        case "forge" =>
          lowerBoundVersion.flatMap {
            //This will crash and burn if the implementation becomes
            //something else, but better that, than failing silently
            case version: DefaultArtifactVersion =>
              if (BigInt(version.version.getFirstInteger) >= 28) {
                Some(version.toString) //Not sure what we really want to do here
              } else {
                version.toString match {
                  case OldForgeVersion(version) => Some(version)
                  case _                        => None
                }
              }
          } -> None //TODO
        case _ => None -> None
      }
    }

    APIV2.VersionPlatform(
      platform,
      platformVersion.filterNot(_ == "null"),
      displayAndMc.flatMap(_._1),
      displayAndMc.flatMap(_._2)
    )
  }
}

case class APIV2QueryCompactProject(
    pluginId: String,
    name: String,
    namespace: ProjectNamespace,
    promotedVersions: Json,
    views: Long,
    downloads: Long,
    recentViews: Long,
    recentDownloads: Long,
    stars: Long,
    watchers: Long,
    category: Category,
    visibility: Visibility
) {
  def asProtocol: Either[DecodingFailure, APIV2.CompactProject] =
    APIV2QueryProject.decodePromotedVersions(promotedVersions).map { decodedPromotedVersions =>
      APIV2.CompactProject(
        pluginId,
        name,
        APIV2.ProjectNamespace(
          namespace.ownerName,
          namespace.slug
        ),
        decodedPromotedVersions,
        APIV2.ProjectStatsAll(
          views,
          downloads,
          recentViews,
          recentDownloads,
          stars,
          watchers
        ),
        category,
        visibility
      )
    }
}

case class APIV2QueryMember(
    user: String,
    role: Role,
    isAccepted: Boolean
) {

  def asProtocol: APIV2.Member = APIV2.Member(
    user,
    APIV2.Role(
      role,
      role.title,
      role.color.hex,
      role.permissions.toNamedSeq.toList,
      isAccepted
    )
  )
}

case class APIV2QueryVersion(
    createdAt: OffsetDateTime,
    name: String,
    dependenciesIds: List[String],
    dependenciesVersions: List[Option[String]],
    visibility: Visibility,
    downloads: Long,
    fileSize: Long,
    md5Hash: String,
    fileName: String,
    authorName: Option[String],
    reviewState: ReviewState,
    mixin: Boolean,
    stability: Stability,
    releaseType: Option[ReleaseType],
    platforms: List[String],
    platformVersions: List[Option[String]],
    postId: Option[Int]
) {

  def asProtocol: APIV2.Version = APIV2.Version(
    createdAt,
    name,
    dependenciesIds.zip(dependenciesVersions).map {
      case (id, Some("null"))  => APIV2.VersionDependency(id, None)
      case (id, Some(version)) => APIV2.VersionDependency(id, Some(version))
      case (id, None)          => APIV2.VersionDependency(id, None)
    },
    visibility,
    APIV2.VersionStatsAll(downloads),
    APIV2.FileInfo(fileName, fileSize, md5Hash),
    authorName,
    reviewState,
    APIV2.VersionTags(
      mixin,
      stability,
      releaseType,
      platforms.zip(platformVersions).map {
        case (platform, platformVersion) =>
          APIV2QueryProject.decodeVersionPlatform(platform, platformVersion)
      }
    ),
    APIV2.VersionExternal(
      APIV2.VersionExternalDiscourse(
        postId
      )
    )
  )
}

case class APIV2QueryUser(
    createdAt: OffsetDateTime,
    name: String,
    tagline: Option[String],
    joinDate: Option[OffsetDateTime],
    projectCount: Long,
    roles: List[Role]
) {

  def asProtocol: APIV2.User = APIV2.User(
    createdAt,
    name,
    tagline,
    joinDate,
    projectCount,
    roles.map { role =>
      APIV2.Role(
        role,
        role.title,
        role.color.hex,
        role.permissions.toNamedSeq.toList,
        isAccepted = true
      )
    }
  )
}

case class APIV2QueryOrganization(
    owner: String,
    createdAt: OffsetDateTime,
    name: String,
    tagline: Option[String],
    joinDate: Option[OffsetDateTime],
    projectCount: Long,
    roles: List[Role]
) {

  def asProtocol: APIV2.Organization = APIV2.Organization(
    owner,
    APIV2.User(
      createdAt,
      name,
      tagline,
      joinDate,
      projectCount,
      roles.map { role =>
        APIV2.Role(
          role,
          role.title,
          role.color.hex,
          role.permissions.toNamedSeq.toList,
          isAccepted = true
        )
      }
    )
  )
}

case class APIV2QueryMembership(
    scope: String,
    organization: Option[String],
    pluginId: Option[String],
    ownerName: Option[String],
    slug: Option[String],
    role: Role,
    isAccepted: Boolean
) {

  def asProtocol: APIV2.Membership = APIV2.Membership(
    scope,
    organization.map(APIV2.MembershipOrganization.apply),
    pluginId
      .zip(ownerName.zip(slug).map((APIV2.ProjectNamespace.apply _).tupled))
      .map((APIV2.MembershipProject.apply _).tupled),
    role,
    isAccepted
  )
}

case class APIV2ProjectStatsQuery(
    day: LocalDate,
    downloads: Long,
    views: Int
)
object APIV2ProjectStatsQuery {

  def asProtocol(stats: Seq[APIV2ProjectStatsQuery]): Map[String, APIV2.ProjectStatsDay] =
    //We use a TreeMap to keep stuff sorted. Technically it shouldn't make a difference for JSON, but it's easier to work with when debugging.
    stats
      .groupMapReduce(_.day.toString)(d => APIV2.ProjectStatsDay(d.downloads, d.views.toLong))((v1, v2) =>
        APIV2.ProjectStatsDay(v1.downloads + v2.downloads, v1.views + v2.views)
      )
      .to(TreeMap)
}

case class APIV2VersionStatsQuery(
    day: LocalDate,
    downloads: Int
)
object APIV2VersionStatsQuery {

  def asProtocol(stats: Seq[APIV2VersionStatsQuery]): Map[String, APIV2.VersionStatsDay] =
    //We use a TreeMap to keep stuff sorted. Technically it shouldn't make a difference for JSON, but it's easier to work with when debugging.
    stats
      .groupMapReduce(_.day.toString)(d => APIV2.VersionStatsDay(d.downloads.toLong))((v1, v2) =>
        APIV2.VersionStatsDay(v1.downloads + v2.downloads)
      )
      .to(TreeMap)
}
