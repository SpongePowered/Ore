package db.impl.query.apiv2

import java.time.LocalDate

import models.protocols.APIV2
import models.querymodels.{APIV2ProjectStatsQuery, APIV2QueryCompactProject, APIV2VersionStatsQuery}
import ore.db.DbRef
import ore.models.project.{Project, ProjectSortingStrategy}
import ore.models.user.User

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.implicits.javasql._
import doobie.implicits.javatime.JavaTimeLocalDateMeta
import io.circe.DecodingFailure

object ActionsAndStatsQueries extends APIV2Queries {

  private def actionFrag(
      table: Fragment,
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Fragment = {
    val base =
      sql"""|SELECT p.plugin_id,
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
            |       p.visibility
            |    FROM users u JOIN $table psw ON u.id = psw.user_id
            |             JOIN projects p ON psw.project_id = p.id JOIN project_stats ps ON psw.project_id = ps.id """.stripMargin

    val filters = Fragments.whereAndOpt(
      Some(fr"u.name = $user"),
      visibilityFrag(canSeeHidden, currentUserId, fr0"p")
    )

    base ++ filters
  }

  private def actionQuery(
      table: Fragment,
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      limit: Long,
      offset: Long
  ): Query0[Either[DecodingFailure, APIV2.CompactProject]] = {
    val ordering = order.fragment

    val select = actionFrag(table, user, canSeeHidden, currentUserId)
    (select ++ fr"ORDER BY" ++ ordering ++ fr"LIMIT $limit OFFSET $offset")
      .query[APIV2QueryCompactProject]
      .map(_.asProtocol)
  }

  private def actionCountQuery(
      table: Fragment,
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] =
    countOfSelect(actionFrag(table, user, canSeeHidden, currentUserId))

  def starredQuery(
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      limit: Long,
      offset: Long
  ): Query0[Either[DecodingFailure, APIV2.CompactProject]] =
    actionQuery(Fragment.const("project_stars"), user, canSeeHidden, currentUserId, order, limit, offset)

  def starredCountQuery(
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] = actionCountQuery(Fragment.const("project_stars"), user, canSeeHidden, currentUserId)

  def watchingQuery(
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      limit: Long,
      offset: Long
  ): Query0[Either[DecodingFailure, APIV2.CompactProject]] =
    actionQuery(Fragment.const("project_watchers"), user, canSeeHidden, currentUserId, order, limit, offset)

  def watchingCountQuery(
      user: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] = actionCountQuery(Fragment.const("project_watchers"), user, canSeeHidden, currentUserId)

  def projectStats(
      projectId: DbRef[Project],
      startDate: LocalDate,
      endDate: LocalDate
  ): Query0[APIV2ProjectStatsQuery] =
    sql"""|SELECT CAST(dates.day AS DATE), coalesce(sum(pvd.downloads), 0) AS downloads, coalesce(pv.views, 0) AS views
          |    FROM projects p,
          |         (SELECT generate_series($startDate::DATE, $endDate::DATE, INTERVAL '1 DAY') AS day) dates
          |             LEFT JOIN project_versions_downloads pvd ON dates.day = pvd.day
          |             LEFT JOIN project_views pv ON dates.day = pv.day AND pvd.project_id = pv.project_id
          |    WHERE p.id = $projectId
          |      AND (pvd IS NULL OR pvd.project_id = p.id)
          |    GROUP BY pv.views, dates.day;""".stripMargin.query[APIV2ProjectStatsQuery]

  def versionStats(
      projectId: DbRef[Project],
      versionString: String,
      startDate: LocalDate,
      endDate: LocalDate
  ): Query0[APIV2VersionStatsQuery] =
    sql"""|SELECT CAST(dates.day AS DATE), coalesce(pvd.downloads, 0) AS downloads
          |    FROM projects p,
          |         project_versions pv,
          |         (SELECT generate_series($startDate::DATE, $endDate::DATE, INTERVAL '1 DAY') AS day) dates
          |             LEFT JOIN project_versions_downloads pvd ON dates.day = pvd.day
          |    WHERE p.id = $projectId
          |      AND pv.version_string = $versionString
          |      AND (pvd IS NULL OR (pvd.project_id = p.id AND pvd.version_id = pv.id));""".stripMargin
      .query[APIV2VersionStatsQuery]
}
