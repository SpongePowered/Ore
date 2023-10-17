package db.impl.query

import ore.db.DbRef
import ore.db.impl.query.DoobieOreProtocol
import ore.models.project.{Project, Version}
import ore.models.user.User

import com.github.tminglei.slickpg.InetString
import doobie._
import doobie.implicits._

object StatTrackerQueries extends DoobieOreProtocol {

  def addVersionDownload(
      projectId: DbRef[Project],
      versionId: DbRef[Version],
      address: InetString,
      cookie: String,
      userId: Option[DbRef[User]]
  ): Update0 =
    sql"""|INSERT INTO project_versions_downloads_individual (created_at, project_id, version_id, address, cookie, user_id)
          |    VALUES (now(), $projectId, $versionId, $address, $cookie, $userId);""".stripMargin.update

  def addProjectView(
      projectId: DbRef[Project],
      address: InetString,
      cookie: String,
      userId: Option[DbRef[User]]
  ): Update0 =
    sql"""|INSERT INTO project_views_individual (created_at, project_id, address, cookie, user_id)
          |    VALUES (now(), $projectId, $address, $cookie, $userId);""".stripMargin.update

  private def findStatsCookie(table: String, address: InetString, userId: Option[DbRef[User]]) =
    sql"""|SELECT cookie
          |    FROM ${Fragment.const(table)}
          |    WHERE address = $address
          |       OR (user_id IS NOT NULL AND user_id = $userId) LIMIT 1;""".stripMargin

  def findVersionDownloadCookie(
      address: InetString,
      userId: Option[DbRef[User]]
  ): Query0[String] = findStatsCookie("project_versions_downloads_individual", address, userId).query[String]

  def findProjectViewCookie(
      address: InetString,
      userId: Option[DbRef[User]]
  ): Query0[String] = findStatsCookie("project_views_individual", address, userId).query[String]

  private def fillStatsUserIdsFromOthers(table: String): Update0 =
    sql"""|UPDATE ${Fragment.const(table)} pvdi
          |SET user_id = (SELECT pvdi2.user_id
          |                   FROM ${Fragment.const(table)} pvdi2
          |                   WHERE pvdi2.user_id IS NOT NULL
          |                     AND pvdi2.cookie = pvdi.cookie
          |                   LIMIT 1)
          |    WHERE pvdi.user_id IS NULL
          |      AND pvdi.processed = 0;""".stripMargin.update

  private def processStatsMain(
      individualTable: String,
      dayTable: String,
      statColumn: String,
      withUserId: Boolean,
      includeVersionId: Boolean
  ): Update0 = {
    val withUserIdCond = if (withUserId) fr"user_id IS NOT NULL" else fr"user_id IS NULL"
    val retColumn      = if (withUserId) fr"user_id" else fr"address"
    val versionIdColumn: Option[String] => Fragment =
      if (includeVersionId) {
        case None    => fr"version_id,"
        case Some(s) => fr"${Fragment.const(s)}.version_id,"
      }
      else _ => fr""
    val conflictColumn = if (includeVersionId) fr"version_id" else fr"project_id"

    val statColumnFrag = Fragment.const(statColumn)

    sql"""|WITH d AS (
          |    UPDATE ${Fragment.const(individualTable)} SET processed = processed + 1
          |        WHERE $withUserIdCond
          |        RETURNING created_at, project_id, ${versionIdColumn(None)} $retColumn, processed
          |)
          |INSERT
          |    INTO ${Fragment.const(dayTable)} AS pvd (day, project_id, ${versionIdColumn(None)} $statColumnFrag)
          |SELECT sq.day,
          |       sq.project_id,
          |       ${versionIdColumn(Some("sq"))}
          |       count(DISTINCT sq.$retColumn) FILTER ( WHERE sq.processed <@ ARRAY [1] )
          |    FROM (SELECT date_trunc('DAY', d.created_at) AS day,
          |                 d.project_id,
          |                 ${versionIdColumn(Some("d"))}
          |                 $retColumn,
          |                 array_agg(d.processed)    AS processed
          |              FROM d
          |              GROUP BY date_trunc('DAY', d.created_at), d.project_id, ${versionIdColumn(Some("D"))} $retColumn) sq
          |    GROUP BY sq.day, ${versionIdColumn(Some("sq"))} sq.project_id
          |ON CONFLICT (DAY, $conflictColumn) DO UPDATE SET $statColumnFrag = pvd.$statColumnFrag + excluded.$statColumnFrag""".stripMargin.update
  }

  private def deleteOldIndividual(individualTable: String) =
    sql"""DELETE FROM ${Fragment.const(individualTable)} WHERE processed != 0 AND created_at < now() + '30 days'::INTERVAL""".update

  private def processStats(individualTable: String, dayTable: String, statColumn: String, includeVersionId: Boolean) =
    Seq(
      fillStatsUserIdsFromOthers(individualTable),
      processStatsMain(individualTable, dayTable, statColumn, withUserId = true, includeVersionId = includeVersionId),
      processStatsMain(individualTable, dayTable, statColumn, withUserId = false, includeVersionId = includeVersionId),
      deleteOldIndividual(individualTable)
    )

  val processVersionDownloads: Seq[Update0] = processStats(
    "project_versions_downloads_individual",
    "project_versions_downloads",
    "downloads",
    includeVersionId = true
  )
  val processProjectViews: Seq[Update0] =
    processStats("project_views_individual", "project_views", "views", includeVersionId = false)
}
