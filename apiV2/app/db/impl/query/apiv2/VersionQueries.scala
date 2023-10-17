package db.impl.query.apiv2

import controllers.apiv2.Versions
import models.protocols.APIV2
import models.querymodels.APIV2QueryVersion
import ore.{OreConfig, OrePlatform}
import ore.db.DbRef
import ore.models.project.{Project, Version}
import ore.models.user.User

import cats.data.NonEmptyList
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.implicits.javasql._
import doobie.implicits.javatime.JavaTimeLocalDateMeta

object VersionQueries extends APIV2Queries {

  def versionSelectFrag(
      projectId: DbRef[Project],
      versionName: Option[String],
      platforms: List[(String, Option[String])],
      stability: List[Version.Stability],
      releaseType: List[Version.ReleaseType],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  )(implicit config: OreConfig): Fragment = {
    val base =
      sql"""|SELECT pv.created_at,
            |       pv.version_string,
            |       pv.dependency_ids,
            |       pv.dependency_versions,
            |       pv.visibility,
            |       coalesce((SELECT sum(pvd.downloads) FROM project_versions_downloads pvd WHERE p.id = pvd.project_id AND pv.id = pvd.version_id), 0),
            |       pv.file_size,
            |       pv.hash,
            |       pv.file_name,
            |       u.name,
            |       pv.review_state,
            |       pv.uses_mixin,
            |       pv.stability,
            |       pv.release_type,
            |       coalesce(array_agg(pvp.platform) FILTER ( WHERE pvp.platform IS NOT NULL ), ARRAY []::TEXT[]),
            |       coalesce(array_agg(pvp.platform_version) FILTER ( WHERE pvp.platform IS NOT NULL ), ARRAY []::TEXT[]),
            |       pv.post_id
            |    FROM projects p
            |             JOIN project_versions pv ON p.id = pv.project_id
            |             LEFT JOIN users u ON pv.author_id = u.id
            |             LEFT JOIN project_version_platforms pvp ON pv.id = pvp.version_id """.stripMargin

    val coarsePlatforms = platforms.map {
      case (name, optVersion) =>
        (
          name,
          optVersion.map(version =>
            config.ore.platformsByName.get(name).fold(version)(OrePlatform.coarseVersionOf(_)(version))
          )
        )
    }

    val filters = Fragments.whereAndOpt(
      Some(fr"p.id = $projectId"),
      versionName.map(v => fr"pv.version_string = $v"),
      Option.when(coarsePlatforms.nonEmpty)(
        Fragments.or(
          coarsePlatforms.map {
            case (platform, Some(version)) => fr"pvp.platform = $platform AND pvp.platform_coarse_version = $version"
            case (platform, None)          => fr"pvp.platform = $platform"
          }: _*
        )
      ),
      NonEmptyList.fromList(stability).map(Fragments.in(fr"pv.stability", _)),
      NonEmptyList.fromList(releaseType).map(Fragments.in(fr"pv.release_type", _)),
      visibilityFrag(canSeeHidden, currentUserId, fr0"pv")
    )

    base ++ filters ++ fr"GROUP BY p.id, pv.id, u.id"
  }

  def versionQuery(
      projectId: DbRef[Project],
      versionName: Option[String],
      platforms: List[(String, Option[String])],
      stability: List[Version.Stability],
      releaseType: List[Version.ReleaseType],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]],
      limit: Long,
      offset: Long
  )(implicit config: OreConfig): Query0[APIV2.Version] =
    (versionSelectFrag(
      projectId,
      versionName,
      platforms,
      stability,
      releaseType,
      canSeeHidden,
      currentUserId
    ) ++ fr"ORDER BY pv.created_at DESC LIMIT $limit OFFSET $offset")
      .query[APIV2QueryVersion]
      .map(_.asProtocol)

  def singleVersionQuery(
      projectId: DbRef[Project],
      versionName: String,
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  )(implicit config: OreConfig): doobie.Query0[APIV2.Version] =
    versionQuery(projectId, Some(versionName), Nil, Nil, Nil, canSeeHidden, currentUserId, 1, 0)

  def versionCountQuery(
      projectId: DbRef[Project],
      platforms: List[(String, Option[String])],
      stability: List[Version.Stability],
      releaseType: List[Version.ReleaseType],
      canSeeHidden: Boolean,
      currentUserId: Option[DbRef[User]]
  )(implicit config: OreConfig): Query0[Long] =
    countOfSelect(
      versionSelectFrag(projectId, None, platforms, stability, releaseType, canSeeHidden, currentUserId)
    )

  def updateVersion(
      projectId: DbRef[Project],
      versionName: String,
      edits: Versions.DbEditableVersion
  ): Update0 = {
    val versionColumns = Versions.DbEditableVersionF[Column](
      Column.arg("stability"),
      Column.opt("release_type")
    )

    (updateTable("project_versions", versionColumns, edits) ++ fr" WHERE project_id = $projectId AND version_string = $versionName").update
  }
}
