package models.querymodels

import java.time.{LocalDate, OffsetDateTime}

import scala.collection.immutable.TreeMap
import scala.jdk.CollectionConverters._
import scala.util.Try

import play.api.mvc.RequestHeader

import models.protocols.APIV2
import ore.OreConfig
import ore.data.project.{Category, ProjectNamespace}
import ore.models.project.io.ProjectFiles
import ore.models.project.{ReviewState, TagColor, Visibility}
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
    userStarred: Boolean,
    userWatching: Boolean,
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
          homepage,
          issues,
          sources,
          support,
          APIV2.ProjectLicense(licenseName, licenseUrl),
          forumSync
        ),
        iconUrl
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
          version <- cursor.get[String]("version_string")
          tagName <- cursor.get[String]("tag_name")
          data    <- cursor.get[Option[String]]("tag_version")
          color <- cursor
            .get[Int]("tag_color")
            .flatMap { i =>
              TagColor
                .withValueOpt(i)
                .toRight(DecodingFailure(s"Invalid TagColor $i", cursor.downField("tag_color").history))
            }
        } yield {

          val displayAndMc = data.map { rawData =>
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

            tagName match {
              case "Sponge" =>
                lowerBoundVersionStr.collect {
                  case MajorMinor(version) => version
                } -> None //TODO
              case "SpongeForge" =>
                unzipOptions(
                  lowerBoundVersionStr.collect {
                    case SpongeForgeMajorMinorMC(version, mcVersion) => version -> mcVersion
                  }
                )
              case "SpongeVanilla" =>
                unzipOptions(
                  lowerBoundVersionStr.collect {
                    case SpongeVanillaMajorMinorMC(version, mcVersion) => version -> mcVersion
                  }
                )
              case "Forge" =>
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

          APIV2.PromotedVersion(
            version,
            Seq(
              APIV2.PromotedVersionTag(
                tagName,
                data,
                displayAndMc.flatMap(_._1),
                displayAndMc.flatMap(_._2),
                APIV2.VersionTagColor(
                  color.foreground,
                  color.background
                )
              )
            )
          )
        }
      }
    } yield res
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

case class APIV2QueryProjectMember(
    user: String,
    roles: List[Role]
) {

  def asProtocol: APIV2.ProjectMember = APIV2.ProjectMember(
    user,
    roles.map { role =>
      APIV2.Role(
        role.value,
        role.title,
        role.color.hex
      )
    }
  )
}

case class APIV2QueryVersion(
    createdAt: OffsetDateTime,
    name: String,
    dependenciesIds: List[String],
    visibility: Visibility,
    description: Option[String],
    downloads: Long,
    fileSize: Long,
    md5Hash: String,
    fileName: String,
    authorName: Option[String],
    reviewState: ReviewState,
    tags: List[APIV2QueryVersionTag]
) {

  def asProtocol: APIV2.Version = APIV2.Version(
    createdAt,
    name,
    dependenciesIds.map { depId =>
      val data = depId.split(":")
      APIV2.VersionDependency(
        data(0),
        data.lift(1)
      )
    },
    visibility,
    description,
    APIV2.VersionStatsAll(downloads),
    APIV2.FileInfo(name, fileSize, md5Hash),
    authorName,
    reviewState,
    tags.map(_.asProtocol)
  )
}

case class APIV2QueryVersionTag(
    name: String,
    data: Option[String],
    color: TagColor
) {

  def asProtocol: APIV2.VersionTag = APIV2.VersionTag(
    name,
    data,
    APIV2.VersionTagColor(
      color.foreground,
      color.background
    )
  )
}

case class APIV2QueryUser(
    createdAt: OffsetDateTime,
    name: String,
    tagline: Option[String],
    joinDate: Option[OffsetDateTime],
    roles: List[Role]
) {

  def asProtocol: APIV2.User = APIV2.User(
    createdAt,
    name,
    tagline,
    joinDate,
    roles.map { role =>
      APIV2.Role(
        role.value,
        role.title,
        role.color.hex
      )
    }
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
