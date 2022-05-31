package db.impl.query.apiv2

import play.api.mvc.RequestHeader

import controllers.apiv2.Projects
import models.protocols.APIV2
import models.querymodels.APIV2QueryProject
import ore.{OreConfig, OrePlatform}
import ore.data.project.Category
import ore.db.DbRef
import ore.models.project.{ProjectSortingStrategy, Version}
import ore.models.project.io.ProjectFiles
import ore.models.user.User

import cats.data.NonEmptyList
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.implicits.javasql._
import doobie.implicits.javatime.JavaTimeLocalDateMeta
import zio.ZIO
import zio.blocking.Blocking

object ProjectQueries extends APIV2Queries {

  def projectSelectFrag(
      projectSlug: Option[String],
      category: List[Category],
      platforms: List[(String, Option[String])],
      stability: List[Version.Stability],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      exactSearch: Boolean
  )(implicit config: OreConfig): Fragment = {
    val userActionsTaken = currentUserId.fold(fr"FALSE, FALSE,") { id =>
      fr"""|EXISTS(SELECT * FROM project_stars s WHERE s.project_id = p.id AND s.user_id = $id)    AS user_stared,
           |EXISTS(SELECT * FROM project_watchers S WHERE S.project_id = p.id AND S.user_id = $id) AS user_watching,""".stripMargin
    }

    val base =
      sql"""|SELECT p.created_at,
            |       p.plugin_id,
            |       p.name,
            |       p.owner_name,
            |       p.slug,
            |       to_jsonb(
            |               ARRAY(SELECT jsonb_build_object(
            |                                    'version_string', promoted.version_string,
            |                                    'platforms', promoted.platforms,
            |                                    'platform_versions', promoted.platform_versions,
            |                                    'platform_coarse_versions', promoted.platform_coarse_versions,
            |                                    'stability', promoted.stability,
            |                                    'release_type', promoted.release_type)
            |                         FROM promoted_versions promoted
            |                         WHERE promoted.project_id = p.id
            |                         ORDER BY promoted.platform_coarse_versions DESC LIMIT 5)) AS promoted_versions,
            |       ps.views,
            |       ps.downloads,
            |       ps.recent_views,
            |       ps.recent_downloads,
            |       ps.stars,
            |       ps.watchers,
            |       p.category,
            |       p.description,
            |       ps.last_updated,
            |       p.visibility,
            |       p.topic_id,
            |       p.post_id,
            |       $userActionsTaken
            |       p.keywords,
            |       p.homepage,
            |       p.issues,
            |       p.source,
            |       p.support,
            |       p.license_name,
            |       p.license_url,
            |       p.forum_sync
            |  FROM projects p JOIN project_stats ps ON p.id = ps.id """.stripMargin

    val (platformsWithVersion, platformsWithoutVersion) = platforms.partitionEither {
      case (name, Some(version)) =>
        Left((name, config.ore.platformsByName.get(name).fold(version)(OrePlatform.coarseVersionOf(_)(version))))
      case (name, None) => Right(name)
    }

    val filters = Fragments.whereAndOpt(
      projectSlug.map(slug => fr"lower(p.slug) = lower($slug)"),
      NonEmptyList.fromList(category).map(Fragments.in(fr"p.category", _)),
      if (platforms.nonEmpty || stability.nonEmpty) {
        val jsSelect =
          sql"""|SELECT promoted.platform
                |    FROM (SELECT unnest(ppv.platforms)                AS platform,
                |                 unnest(ppv.platform_coarse_versions) AS platform_coarse_version,
                |                 ppv.stability
                |              FROM promoted_versions ppv) AS promoted """.stripMargin ++
            Fragments.whereAndOpt(
              NonEmptyList
                .fromList(platformsWithVersion)
                .map(t => in2(fr"(promoted.platform, promoted.platform_coarse_version)", t)),
              NonEmptyList.fromList(platformsWithoutVersion).map(t => Fragments.in(fr"promoted.platform", t)),
              NonEmptyList.fromList(stability).map(Fragments.in(fr"promoted.stability", _))
            )

        Some(fr"EXISTS" ++ Fragments.parentheses(jsSelect))
      } else
        None,
      query.map { q =>
        val trimmedQ = q.trim

        if (exactSearch) {
          fr"lower(p.slug) = lower($trimmedQ)"
        } else {
          if (q.endsWith(" ")) fr"p.search_words @@ websearch_to_tsquery('english', $trimmedQ)"
          else fr"p.search_words @@ websearch_to_tsquery_postfix('english', $trimmedQ)"
        }
      },
      owner.map(o => fr"p.owner_name = $o"),
      visibilityFrag(canSeeHidden, currentUserId, fr0"p")
    )

    val groupBy =
      fr"GROUP BY p.id, ps.views, ps.downloads, ps.recent_views, ps.recent_downloads, ps.stars, ps.watchers, ps.last_updated"

    base ++ filters ++ groupBy
  }

  def projectQuery(
      projectSlug: Option[String],
      category: List[Category],
      platforms: List[(String, Option[String])],
      stability: List[Version.Stability],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      orderWithRelevance: Boolean,
      exactSearch: Boolean,
      limit: Long,
      offset: Long
  )(
      implicit projectFiles: ProjectFiles[ZIO[Blocking, Nothing, *]],
      requestHeader: RequestHeader,
      config: OreConfig
  ): Query0[ZIO[Blocking, Nothing, APIV2.Project]] = {
    val ordering = if (orderWithRelevance && query.nonEmpty) {
      val relevance = query.fold(fr"1") { q =>
        val trimmedQ = q.trim

        if (q.endsWith(" ")) fr"ts_rank(p.search_words, websearch_to_tsquery('english', $trimmedQ)) DESC"
        else fr"ts_rank(p.search_words, websearch_to_tsquery_postfix('english', $trimmedQ)) DESC"
      }

      // 1483056000 is the Ore epoch
      // 86400 seconds to days
      // 604800‬ seconds to weeks
      order match {
        case ProjectSortingStrategy.MostStars     => fr"ps.stars *" ++ relevance
        case ProjectSortingStrategy.MostDownloads => fr"(ps.downloads / 100) *" ++ relevance
        case ProjectSortingStrategy.MostViews     => fr"(ps.views / 200) *" ++ relevance
        case ProjectSortingStrategy.Newest =>
          fr"((EXTRACT(EPOCH FROM p.created_at) - 1483056000) / 86400) *" ++ relevance
        case ProjectSortingStrategy.RecentlyUpdated =>
          fr"((EXTRACT(EPOCH FROM ps.last_updated) - 1483056000) / 604800) *" ++ relevance
        case ProjectSortingStrategy.OnlyRelevance   => relevance
        case ProjectSortingStrategy.RecentViews     => fr"ps.recent_views *" ++ relevance
        case ProjectSortingStrategy.RecentDownloads => fr"ps.recent_downloads*" ++ relevance
      }
    } else order.fragment

    val select = projectSelectFrag(
      projectSlug,
      category,
      platforms,
      stability,
      query,
      owner,
      canSeeHidden,
      currentUserId,
      exactSearch
    )
    (select ++ fr"ORDER BY" ++ ordering ++ fr"LIMIT $limit OFFSET $offset").query[APIV2QueryProject].map(_.asProtocol)
  }

  def singleProjectQuery(
      projectOwner: String,
      projectSlug: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  )(
      implicit projectFiles: ProjectFiles[ZIO[Blocking, Nothing, *]],
      requestHeader: RequestHeader,
      config: OreConfig
  ): Query0[ZIO[Blocking, Nothing, APIV2.Project]] =
    projectQuery(
      Some(projectSlug),
      Nil,
      Nil,
      Nil,
      None,
      Some(projectOwner),
      canSeeHidden,
      currentUserId,
      ProjectSortingStrategy.Default,
      orderWithRelevance = false,
      exactSearch = false,
      1,
      0
    )

  def projectCountQuery(
      projectSlug: Option[String],
      category: List[Category],
      platforms: List[(String, Option[String])],
      stability: List[Version.Stability],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      exactSearch: Boolean
  )(implicit config: OreConfig): Query0[Long] =
    countOfSelect(
      projectSelectFrag(
        projectSlug,
        category,
        platforms,
        stability,
        query,
        owner,
        canSeeHidden,
        currentUserId,
        exactSearch
      )
    )

  def updateProject(projectOwner: String, projectSlug: String, edits: Projects.EditableProject): Update0 = {
    val projectColumns = Projects.EditableProjectF[Column](
      Column.arg("name"),
      Projects.EditableProjectNamespaceF[Column](Column.arg("owner_name")),
      Column.arg("category"),
      Column.opt("description"),
      Projects.EditableProjectSettingsF[Column](
        Column.arg("keywords"),
        Column.opt("homepage"),
        Column.opt("issues"),
        Column.opt("source"),
        Column.opt("support"),
        Projects.EditableProjectLicenseF[Column](
          Column.opt("license_name"),
          Column.opt("license_url")
        ),
        Column.arg("forum_sync")
      )
    )

    import cats.instances.option._
    import cats.instances.tuple._

    val (newOwnerSet, newOwnerFrom, newOwnerFilter) = edits.namespace.owner.foldMap { owner =>
      (fr", owner_id = u.id", fr"FROM users u", fr"AND u.name = $owner")
    }

    (updateTable("projects", projectColumns, edits) ++ newOwnerSet ++ newOwnerFrom ++ fr" WHERE owner_name = $projectOwner AND lower(slug) = lower($projectSlug) " ++ newOwnerFilter).update
  }
}
