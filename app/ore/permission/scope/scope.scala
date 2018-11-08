package ore.permission.scope
import db.ObjectReference

sealed trait Scope
object Scope {
  implicit val globalScopeHasScope: HasScope[GlobalScope.type]        = (a: GlobalScope.type) => a
  implicit val projectScopeHasScope: HasScope[ProjectScope]           = (a: ProjectScope) => a
  implicit val organizationScopeHasScope: HasScope[OrganizationScope] = (a: OrganizationScope) => a
}

case object GlobalScope                           extends Scope
case class ProjectScope(id: ObjectReference)      extends Scope
case class OrganizationScope(id: ObjectReference) extends Scope
