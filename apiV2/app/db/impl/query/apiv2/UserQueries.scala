package db.impl.query.apiv2

import controllers.apiv2.Users
import controllers.apiv2.Users.UserSortingStrategy
import models.protocols.APIV2
import models.querymodels.{APIV2QueryMember, APIV2QueryMembership, APIV2QueryUser}
import ore.db.DbRef
import ore.models.organization.Organization
import ore.models.project.Project
import ore.permission.role.Role

import cats.data.NonEmptyList
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.implicits.javasql._
import doobie.implicits.javatime.JavaTimeLocalDateMeta

object UserQueries extends APIV2Queries {

  def userSearchFrag(
      q: Option[String],
      minProjects: Long,
      roles: Seq[Role],
      excludeOrganizations: Boolean
  ): Fragment = {
    val whereFilters = Fragments.whereAndOpt(
      q.map(s => if (s.endsWith("%")) fr"u.name LIKE $s" else fr"u.name LIKE ${s + "%"}"),
      Option.when(excludeOrganizations)(fr"r IS NULL OR r.name != 'Organization'"),
      NonEmptyList.fromList(roles.toList).map(roles => Fragments.in(fr"r.name", roles))
    )
    val outerFilters = Fragments.andOpt(
      Option.when(minProjects > 0)(fr"count(p.plugin_id) >= $minProjects")
    )
    val havingFilters = if (outerFilters == Fragment.empty) Fragment.empty else fr"HAVING" ++ outerFilters

    sql"""|SELECT u.created_at,
          |       u.name,
          |       u.tagline,
          |       u.join_date,
          |       count(p.plugin_id)                             AS projects,
          |       array_remove(array_agg(DISTINCT r.name), NULL) AS roles
          |    FROM users u
          |             LEFT JOIN project_members_all pma ON u.id = pma.user_id
          |             LEFT JOIN projects p ON p.id = pma.id
          |             LEFT JOIN user_global_roles ugr ON u.id = ugr.user_id
          |             LEFT JOIN roles r ON ugr.role_id = r.ID
          |    $whereFilters
          |    GROUP BY u.ID
          |    $havingFilters""".stripMargin
  }

  def userSearchQuery(
      q: Option[String],
      minProjects: Long,
      roles: Seq[Role],
      excludeOrganizations: Boolean,
      strategy: Users.UserSortingStrategy,
      sortDescending: Boolean,
      limit: Long,
      offset: Long
  ): Query0[APIV2.User] = {
    val select = userSearchFrag(q, minProjects, roles, excludeOrganizations)

    val primaryRawSort = strategy match {
      case UserSortingStrategy.Name     => fr"name"
      case UserSortingStrategy.Joined   => fr"created_at"
      case UserSortingStrategy.Projects => fr"projects"
    }
    val primarySort = if (sortDescending) primaryRawSort ++ fr"DESC" else primaryRawSort ++ fr"ASC"
    val sortFrag    = if (strategy != UserSortingStrategy.Name) primarySort ++ fr", name" else primarySort

    (select ++ fr" ORDER BY" ++ sortFrag ++ fr"LIMIT $limit OFFSET $offset").query[APIV2QueryUser].map(_.asProtocol)
  }

  def userSearchCountQuery(
      q: Option[String],
      minProjects: Long,
      roles: Seq[Role],
      excludeOrganizations: Boolean
  ): Query0[Long] =
    countOfSelect(userSearchFrag(q, minProjects, roles, excludeOrganizations))

  def userQuery(name: String): Query0[APIV2.User] =
    sql"""|SELECT u.created_at,
          |       u.name,
          |       u.tagline,
          |       u.join_date,
          |       count(DISTINCT p.plugin_id),
          |       array_remove(array_agg(DISTINCT r.name), NULL)
          |    FROM users u
          |             LEFT JOIN user_global_roles ugr ON u.id = ugr.user_id
          |             LEFT JOIN roles r ON ugr.role_id = r.id
          |             LEFT JOIN project_members_all pma ON u.id = pma.user_id
          |             LEFT JOIN projects p ON p.id = pma.id
          |    WHERE u.name = $name
          |    GROUP BY u.id""".stripMargin.query[APIV2QueryUser].map(_.asProtocol)

  def getMemberships(user: String): Query0[APIV2.Membership] =
    sql"""|SELECT 'organization', o.name, NULL, NULL, NULL, uor.role_type, uor.is_accepted
          |    FROM user_organization_roles uor
          |             JOIN users u ON uor.user_id = u.id
          |             JOIN organizations o ON uor.organization_id = o.id
          |    WHERE u.name = $user
          |UNION
          |SELECT 'project', NULL, p.plugin_id, p.owner_name, p.slug, upr.role_type, upr.is_accepted
          |    FROM user_project_roles upr
          |             JOIN users u ON upr.user_id = u.id
          |             JOIN projects p ON upr.project_id = p.id
          |    WHERE u.name = $user""".stripMargin.query[APIV2QueryMembership].map(_.asProtocol)

  private def members(
      subjectTable: Fragment,
      roleTable: Fragment,
      joinRolesOn: (Fragment, Fragment) => Fragment,
      where: Fragment => Fragment,
      limit: Long,
      offset: Long
  ): Query0[APIV2.Member] =
    sql"""|SELECT u.name, r.name, usr.is_accepted
          |  FROM $subjectTable s
          |         JOIN $roleTable usr ON ${joinRolesOn(fr0"s", fr0"usr")}
          |         JOIN users u ON usr.user_id = u.id
          |         JOIN roles r ON usr.role_type = r.name
          |  WHERE ${where(fr0"s")}
          |  ORDER BY r.permission & ~B'1'::BIT(64) DESC LIMIT $limit OFFSET $offset""".stripMargin
      .query[APIV2QueryMember]
      .map(_.asProtocol)

  def projectMembers(projectId: DbRef[Project], limit: Long, offset: Long): Query0[APIV2.Member] =
    members(
      fr"projects",
      fr"user_project_roles",
      (p, upr) => fr"$p.id = $upr.project_id",
      p => fr"$p.id = $projectId",
      limit,
      offset
    )

  def orgaMembers(organizationId: DbRef[Organization], limit: Long, offset: Long): Query0[APIV2.Member] =
    members(
      fr"organizations",
      fr"user_organization_roles",
      (o, opr) => fr"$o.id = $opr.organization_id",
      o => fr"$o.id = $organizationId",
      limit,
      offset
    )
}
