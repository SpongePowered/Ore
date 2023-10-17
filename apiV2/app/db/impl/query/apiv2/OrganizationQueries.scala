package db.impl.query.apiv2

import models.protocols.APIV2
import models.querymodels.APIV2QueryOrganization
import ore.db.DbRef
import ore.models.user.User

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.implicits.javasql._
import doobie.implicits.javatime.JavaTimeLocalDateMeta

object OrganizationQueries extends APIV2Queries {

  def organizationQuery(name: String): Query0[APIV2.Organization] =
    sql"""|SELECT ou.name,
          |       u.created_at,
          |       u.name,
          |       u.tagline,
          |       u.join_date,
          |       count(DISTINCT p.plugin_id),
          |       array_remove(array_agg(DISTINCT r.name), NULL)
          |    FROM organizations o
          |             JOIN users u ON o.user_id = u.id -- Org user
          |             JOIN users ou ON o.owner_id = ou.id -- Org owner
          |             LEFT JOIN user_global_roles ugr ON u.id = ugr.user_id
          |             LEFT JOIN roles r ON ugr.role_id = r.id
          |             LEFT JOIN project_members_all pma ON u.id = pma.user_id
          |             LEFT JOIN projects p ON p.id = pma.id
          |    WHERE u.name = $name
          |    GROUP BY ou.id, u.id""".stripMargin.query[APIV2QueryOrganization].map(_.asProtocol)

  def canUploadToOrg(uploader: DbRef[User], orgName: String): Query0[(DbRef[User], Boolean)] =
    sql"""|SELECT ou.id,
          |       ((coalesce(gt.permission, B'0'::BIT(64)) | coalesce(ot.permission, B'0'::BIT(64))) &
          |        (1::BIT(64) << 12)) = (1::BIT(64) << 12) -- Permission.CreateVersion
          |    FROM organizations o
          |             JOIN users ou ON o.user_id = ou.id
          |             LEFT JOIN user_organization_roles om ON o.id = om.organization_id AND om.user_id = $uploader
          |             LEFT JOIN global_trust gt ON gt.user_id = om.user_id
          |             LEFT JOIN organization_trust ot ON ot.user_id = om.user_id AND ot.organization_id = o.id
          |    WHERE o.name = $orgName""".stripMargin.query[(DbRef[User], Boolean)]
}
