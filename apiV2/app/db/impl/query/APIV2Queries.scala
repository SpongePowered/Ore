package db.impl.query

import java.sql.Timestamp
import java.time.{LocalDate, LocalDateTime}

import play.api.mvc.RequestHeader

import controllers.apiv2.{Projects, Versions}
import controllers.sugar.Requests.ApiAuthInfo
import models.protocols.APIV2
import models.querymodels._
import ore.OreConfig
import ore.data.Platform
import ore.data.project.Category
import ore.db.DbRef
import ore.db.impl.query.DoobieOreProtocol
import ore.models.api.ApiKey
import ore.models.project.io.ProjectFiles
import ore.models.project.ProjectSortingStrategy
import ore.models.user.User
import ore.permission.Permission
import _root_.util.fp.{ApplicativeK, FoldableK}
import _root_.util.syntax._

import cats.arrow.FunctionK
import cats.{Reducible, ~>}
import cats.data.{NonEmptyList, Tuple2K}
import cats.instances.list._
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.Put
import io.circe.DecodingFailure
import zio.ZIO
import zio.blocking.Blocking

object APIV2Queries extends DoobieOreProtocol {

  implicit val localDateTimeMeta: Meta[LocalDateTime] = Meta[Timestamp].timap(_.toLocalDateTime)(Timestamp.valueOf)

  def getApiAuthInfo(token: String): Query0[ApiAuthInfo] =
    sql"""|SELECT u.id,
          |       u.created_at,
          |       u.full_name,
          |       u.name,
          |       u.email,
          |       u.tagline,
          |       u.join_date,
          |       u.read_prompts,
          |       u.is_locked,
          |       u.language,
          |       ak.name,
          |       ak.owner_id,
          |       ak.token,
          |       ak.raw_key_permissions,
          |       aks.token,
          |       aks.expires,
          |       CASE
          |           WHEN u.id IS NULL THEN 1::BIT(64)
          |           ELSE (coalesce(gt.permission, B'0'::BIT(64)) | 1::BIT(64) | (1::BIT(64) << 1) | (1::BIT(64) << 2)) &
          |                coalesce(ak.raw_key_permissions, (-1)::BIT(64))
          |           END
          |    FROM api_sessions aks
          |             LEFT JOIN api_keys ak ON aks.key_id = ak.id
          |             LEFT JOIN users u ON aks.user_id = u.id
          |             LEFT JOIN global_trust gt ON gt.user_id = u.id
          |  WHERE aks.token = $token""".stripMargin.query[ApiAuthInfo]

  def findApiKey(identifier: String, token: String): Query0[(DbRef[ApiKey], DbRef[User])] =
    sql"""SELECT k.id, k.owner_id FROM api_keys k WHERE k.token_identifier = $identifier AND k.token = crypt($token, k.token)"""
      .query[(DbRef[ApiKey], DbRef[User])]

  def createApiKey(
      name: String,
      ownerId: DbRef[User],
      tokenIdentifier: String,
      token: String,
      perms: Permission
  ): doobie.Update0 =
    sql"""|INSERT INTO api_keys (created_at, name, owner_id, token_identifier, token, raw_key_permissions)
          |VALUES (now(), $name, $ownerId, $tokenIdentifier, crypt($token, gen_salt('bf')), $perms)""".stripMargin.update

  def deleteApiKey(name: String, ownerId: DbRef[User]): doobie.Update0 =
    sql"""DELETE FROM api_keys k WHERE k.name = $name AND k.owner_id = $ownerId""".update

  //Like in, but takes a tuple
  def in2[F[_]: Reducible, A: Put, B: Put](f: Fragment, fs: F[(A, B)]): Fragment =
    fs.toList.map { case (a, b) => fr0"($a, $b)" }.foldSmash1(f ++ fr0"IN (", fr",", fr")")

  def array[F[_]: Reducible, A: Put](fs: F[A]): Fragment =
    fs.toList.map(a => fr0"$a").foldSmash1(fr0"ARRAY[", fr",", fr0"]")

  def array2Text[F[_]: Reducible, A: Put, B: Put](fs: F[(A, B)]): Fragment =
    fs.toList.map { case (a, b) => fr0"($a, $b)::TEXT" }.foldSmash1(fr0"ARRAY[", fr",", fr0"]")

  def projectSelectFrag(
      pluginId: Option[String],
      category: List[Category],
      platforms: List[(String, Option[String])],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Fragment = {
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
            |       p.promoted_versions,
            |       p.views,
            |       p.downloads,
            |       p.recent_views,
            |       p.recent_downloads,
            |       p.stars,
            |       p.watchers,
            |       p.category,
            |       p.description,
            |       COALESCE(p.last_updated, p.created_at) AS last_updated,
            |       p.visibility,""".stripMargin ++ userActionsTaken ++
        fr"""|       ps.homepage,
             |       ps.issues,
             |       ps.source,
             |       ps.support,
             |       ps.license_name,
             |       ps.license_url,
             |       ps.forum_sync
             |  FROM home_projects p
             |         JOIN projects ps ON p.id = ps.id""".stripMargin

    val visibilityFrag =
      if (canSeeHidden) None
      else
        currentUserId.fold(Some(fr"(p.visibility = 1)")) { id =>
          Some(fr"(p.visibility = 1 OR ($id = ANY(p.project_members) AND p.visibility != 5))")
        }

    val (platformsWithVersion, platformsWithoutVersion) = platforms.partitionEither {
      case (name, Some(version)) =>
        Left((name, Platform.withValueOpt(name).fold(version)(_.coarseVersionOf(version))))
      case (name, None) => Right(name)
    }

    val filters = Fragments.whereAndOpt(
      pluginId.map(id => fr"p.plugin_id = $id"),
      NonEmptyList.fromList(category).map(Fragments.in(fr"p.category", _)),
      if (platforms.nonEmpty) {
        val jsSelect =
          sql"""|SELECT promoted.platform
                |    FROM (SELECT unnest(platforms)                AS platform,
                |                 unnest(platform_coarse_versions) AS platform_coarse_version
                |              FROM jsonb_to_recordset(p.promoted_versions) AS promoted(platforms TEXT[], platform_coarse_versions TEXT[])) AS promoted """.stripMargin ++
            Fragments.whereAndOpt(
              NonEmptyList
                .fromList(platformsWithVersion)
                .map(t => in2(fr"(promoted.platform, promoted.platform_coarse_version)", t)),
              NonEmptyList.fromList(platformsWithoutVersion).map(t => Fragments.in(fr"promoted.platform", t))
            )

        Some(fr"EXISTS" ++ Fragments.parentheses(jsSelect))
      } else
        None,
      query.map(q => fr"p.search_words @@ websearch_to_tsquery($q)"),
      owner.map(o => fr"p.owner_name = $o"),
      visibilityFrag
    )

    base ++ filters
  }

  def projectQuery(
      pluginId: Option[String],
      category: List[Category],
      platforms: List[(String, Option[String])],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      order: ProjectSortingStrategy,
      orderWithRelevance: Boolean,
      limit: Long,
      offset: Long
  )(
      implicit projectFiles: ProjectFiles[ZIO[Blocking, Nothing, ?]],
      requestHeader: RequestHeader,
      config: OreConfig
  ): Query0[ZIO[Blocking, Nothing, APIV2.Project]] = {
    val ordering = if (orderWithRelevance && query.nonEmpty) {
      val relevance = query.fold(fr"1") { q =>
        fr"ts_rank(p.search_words, websearch_to_tsquery($q)) DESC"
      }
      order match {
        case ProjectSortingStrategy.MostStars       => fr"p.stars *" ++ relevance
        case ProjectSortingStrategy.MostDownloads   => fr"p.downloads*" ++ relevance
        case ProjectSortingStrategy.MostViews       => fr"p.views *" ++ relevance
        case ProjectSortingStrategy.Newest          => fr"EXTRACT(EPOCH FROM p.created_at) *" ++ relevance
        case ProjectSortingStrategy.RecentlyUpdated => fr"EXTRACT(EPOCH FROM p.last_updated) *" ++ relevance
        case ProjectSortingStrategy.OnlyRelevance   => relevance
        case ProjectSortingStrategy.RecentViews     => fr"p.recent_views *" ++ relevance
        case ProjectSortingStrategy.RecentDownloads => fr"p.recent_downloads*" ++ relevance
      }
    } else order.fragment

    val select = projectSelectFrag(pluginId, category, platforms, query, owner, canSeeHidden, currentUserId)
    (select ++ fr"ORDER BY" ++ ordering ++ fr"LIMIT $limit OFFSET $offset").query[APIV2QueryProject].map(_.asProtocol)
  }

  def singleProjectQuery(
      pluginId: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  )(
      implicit projectFiles: ProjectFiles[ZIO[Blocking, Nothing, ?]],
      requestHeader: RequestHeader,
      config: OreConfig
  ): Query0[ZIO[Blocking, Nothing, APIV2.Project]] =
    APIV2Queries.projectQuery(
      Some(pluginId),
      Nil,
      Nil,
      None,
      None,
      canSeeHidden,
      currentUserId,
      ProjectSortingStrategy.Default,
      orderWithRelevance = false,
      1,
      0
    )

  def projectCountQuery(
      pluginId: Option[String],
      category: List[Category],
      platforms: List[(String, Option[String])],
      query: Option[String],
      owner: Option[String],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] = {
    val select = projectSelectFrag(pluginId, category, platforms, query, owner, canSeeHidden, currentUserId)
    (sql"SELECT COUNT(*) FROM " ++ Fragments.parentheses(select) ++ fr"sq").query[Long]
  }

  case class Column[A](name: String, mkElem: A => Param.Elem)
  object Column {
    def arg[A](name: String)(implicit put: Put[A]): Column[A]         = Column(name, Param.Elem.Arg(_, put))
    def opt[A](name: String)(implicit put: Put[A]): Column[Option[A]] = Column(name, Param.Elem.Opt(_, put))
  }

  private def updateTable[F[_[_]]: ApplicativeK: FoldableK](
      table: String,
      columns: F[Column],
      edits: F[Option]
  ): Fragment = {
    import shapeless.Const
    import cats.tagless.syntax.all._

    val applyUpdate = new FunctionK[Tuple2K[Option, Column, *], λ[A => Option[Const[Fragment]#λ[A]]]] {
      override def apply[A](tuple: Tuple2K[Option, Column, A]): Option[Fragment] = {
        val column: Column[A] = tuple.second
        tuple.first.map(value => Fragment.const(column.name) ++ Fragment("= ?", List(column.mkElem(value))))
      }
    }

    val updatesSeq = edits
      .map2K(columns)(applyUpdate)
      .foldMapK[List[Option[Fragment]]](
        FunctionK.lift[λ[A => Option[Const[Fragment]#λ[A]]], λ[A => List[Option[Const[Fragment]#λ[A]]]]](List(_))
      )

    val updates = Fragments.setOpt(updatesSeq: _*)

    sql"""UPDATE """ ++ Fragment.const(table) ++ updates
  }

  def updateProject(pluginId: String, edits: Projects.EditableProject): Update0 = {
    val projectColumns = Projects.EditableProjectF[Column](
      Column.arg("name"),
      Column.arg("owner_name"),
      Column.arg("category"),
      Column.opt("description"),
      Column.opt("homepage"),
      Column.opt("issues"),
      Column.opt("sources"),
      Column.opt("support"),
      Projects.EditableProjectLicenseF[Column](
        Column.opt("license_name"),
        Column.opt("license_url")
      ),
      Column.arg("forum_sync")
    )

    import cats.instances.tuple._
    import cats.instances.option._

    val (ownerSet, ownerFrom, ownerFilter) = edits.ownerName.foldMap { owner =>
      (fr", owner_id = u.id", fr"FROM users u", fr"AND u.name = $owner")
    }

    (updateTable("projects", projectColumns, edits) ++ ownerSet ++ ownerFrom ++ fr"WHERE plugin_id = $pluginId" ++ ownerFilter).update
  }

  def projectMembers(pluginId: String, limit: Long, offset: Long): Query0[APIV2.ProjectMember] =
    sql"""|SELECT u.name, array_agg(r.name)
          |  FROM projects p
          |         JOIN user_project_roles upr ON p.id = upr.project_id
          |         JOIN users u ON upr.user_id = u.id
          |         JOIN roles r ON upr.role_type = r.name
          |  WHERE p.plugin_id = $pluginId
          |  GROUP BY u.name ORDER BY max(r.permission::BIGINT) DESC LIMIT $limit OFFSET $offset""".stripMargin
      .query[APIV2QueryProjectMember]
      .map(_.asProtocol)

  def versionSelectFrag(
      pluginId: String,
      versionName: Option[String],
      platforms: List[(String, Option[String])],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Fragment = {
    val base =
      sql"""|SELECT pv.created_at,
            |       pv.version_string,
            |       pv.dependency_ids,
            |       pv.dependency_versions,
            |       pv.visibility,
            |       (SELECT sum(pvd.downloads) FROM project_versions_downloads pvd WHERE p.id = pvd.project_id AND pv.id = pvd.version_id),
            |       pv.file_size,
            |       pv.hash,
            |       pv.file_name,
            |       u.name,
            |       pv.review_state,
            |       pv.uses_mixin,
            |       pv.stability,
            |       pv.release_type,
            |       pv.platforms,
            |       pv.platform_versions
            |    FROM projects p
            |             JOIN project_versions pv ON p.id = pv.project_id
            |             LEFT JOIN users u ON pv.author_id = u.id """.stripMargin

    val (platformsWithVersion, platformsWithoutVersion) = platforms.partitionEither {
      case (name, Some(version)) =>
        Left((name, Platform.withValueOpt(name).fold(version)(_.coarseVersionOf(version))))
      case (name, None) => Right(name)
    }

    val visibilityFrag =
      if (canSeeHidden) None
      else
        currentUserId.fold(Some(fr"(pv.visibility = 1)")) { id =>
          Some(
            fr"(pv.visibility = 1 OR ($id IN (SELECT pm.user_id FROM project_members_all pm WHERE pm.id = p.id) AND pv.visibility != 5))"
          )
        }

    val filters = Fragments.whereAndOpt(
      Some(fr"p.plugin_id = $pluginId"),
      versionName.map(v => fr"pv.version_string = $v"),
      NonEmptyList.fromList(platformsWithoutVersion).map(array(_) ++ fr"&& pv.platforms"),
      NonEmptyList
        .fromList(platformsWithVersion)
        .map { t =>
          array2Text(t) ++
            fr"&& ARRAY(SELECT (platform, coarse_version)::TEXT FROM unnest(pv.platforms, pv.platform_coarse_versions) as plat(platform, coarse_version))"
        },
      visibilityFrag
    )

    base ++ filters ++ fr"GROUP BY p.id, pv.id, u.id"
  }

  def versionQuery(
      pluginId: String,
      versionName: Option[String],
      platforms: List[(String, Option[String])],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      limit: Long,
      offset: Long
  ): Query0[APIV2.Version] =
    (versionSelectFrag(pluginId, versionName, platforms, canSeeHidden, currentUserId) ++ fr"ORDER BY pv.created_at DESC LIMIT $limit OFFSET $offset")
      .query[APIV2QueryVersion]
      .map(_.asProtocol)

  def singleVersionQuery(
      pluginId: String,
      versionName: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): doobie.Query0[APIV2.Version] = versionQuery(pluginId, Some(versionName), Nil, canSeeHidden, currentUserId, 1, 0)

  def versionCountQuery(
      pluginId: String,
      platforms: List[(String, Option[String])],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  ): Query0[Long] =
    (sql"SELECT COUNT(*) FROM " ++ Fragments.parentheses(
      versionSelectFrag(pluginId, None, platforms, canSeeHidden, currentUserId)
    ) ++ fr"sq").query[Long]

  def updateVersion(pluginId: String, versionName: String, edits: Versions.EditableVersion): Update0 = {
    val versionColumns = Versions.EditableVersionF[Column](
      Column.opt("description"),
      Column.arg("stability"),
      Column.opt("release_type")
    )

    (updateTable("projects", versionColumns, edits) ++ fr"WHERE plugin_id = $pluginId AND version_string = $versionName").update
  }

  def userQuery(name: String): Query0[APIV2.User] =
    sql"""|SELECT u.created_at, u.name, u.tagline, u.join_date, array_agg(r.name)
          |  FROM users u
          |         JOIN user_global_roles ugr ON u.id = ugr.user_id
          |         JOIN roles r ON ugr.role_id = r.id
          |  WHERE u.name = $name
          |  GROUP BY u.id""".stripMargin.query[APIV2QueryUser].map(_.asProtocol)

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
            |       p.promoted_versions,
            |       p.views,
            |       p.downloads,
            |       p.recent_views,
            |       p.recent_downloads,
            |       p.stars,
            |       p.watchers,
            |       p.category,
            |       p.visibility
            |    FROM users u JOIN """.stripMargin ++ table ++
        fr"""|ps ON u.id = ps.user_id
             |             JOIN home_projects p ON ps.project_id = p.id""".stripMargin

    val visibilityFrag =
      if (canSeeHidden) None
      else
        currentUserId.fold(Some(fr"(p.visibility = 1 OR p.visibility = 2)")) { id =>
          Some(fr"(p.visibility = 1 OR p.visibility = 2 OR ($id = ANY(p.project_members) AND p.visibility != 5))")
        }

    val filters = Fragments.whereAndOpt(
      Some(fr"u.name = $user"),
      visibilityFrag
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
  ): Query0[Long] = {
    val select = actionFrag(table, user, canSeeHidden, currentUserId)
    (sql"SELECT COUNT(*) FROM " ++ Fragments.parentheses(select) ++ fr"sq").query[Long]
  }

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

  def projectStats(pluginId: String, startDate: LocalDate, endDate: LocalDate): Query0[APIV2ProjectStatsQuery] =
    sql"""|SELECT CAST(dates.day AS DATE), coalesce(sum(pvd.downloads), 0) AS downloads, coalesce(pv.views, 0) AS views
          |    FROM projects p,
          |         (SELECT generate_series($startDate::DATE, $endDate::DATE, INTERVAL '1 DAY') AS day) dates
          |             LEFT JOIN project_versions_downloads pvd ON dates.day = pvd.day
          |             LEFT JOIN project_views pv ON dates.day = pv.day AND pvd.project_id = pv.project_id
          |    WHERE p.plugin_id = $pluginId
          |      AND (pvd IS NULL OR pvd.project_id = p.id)
          |    GROUP BY pv.views, dates.day;""".stripMargin.query[APIV2ProjectStatsQuery]

  def versionStats(
      pluginId: String,
      versionString: String,
      startDate: LocalDate,
      endDate: LocalDate
  ): Query0[APIV2VersionStatsQuery] =
    sql"""|SELECT CAST(dates.day AS DATE), coalesce(pvd.downloads, 0) AS downloads
          |    FROM projects p,
          |         project_versions pv,
          |         (SELECT generate_series($startDate::DATE, $endDate::DATE, INTERVAL '1 DAY') AS day) dates
          |             LEFT JOIN project_versions_downloads pvd ON dates.day = pvd.day
          |    WHERE p.plugin_id = $pluginId
          |      AND pv.version_string = $versionString
          |      AND (pvd IS NULL OR (pvd.project_id = p.id AND pvd.version_id = pv.id));""".stripMargin
      .query[APIV2VersionStatsQuery]
}
