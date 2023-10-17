package ore.permission.scope

import ore.db.DbRef
import ore.models.organization.Organization
import ore.models.project.Project

sealed trait Scope
object Scope {
  implicit val scopeHasScope: HasScope[Scope] = (a: Scope) => a
}

case object GlobalScope                               extends Scope
case class ProjectScope(id: DbRef[Project])           extends Scope
case class OrganizationScope(id: DbRef[Organization]) extends Scope
