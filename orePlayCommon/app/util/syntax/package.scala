package util

import scala.language.implicitConversions

import ore.auth.AuthUser
import ore.db.Model
import ore.db.access.QueryView
import ore.db.impl.common.Hideable
import ore.markdown.IsMarkdownRenderable
import ore.member.Joinable
import ore.models.organization.{Organization, OrganizationOwned}
import ore.models.project.{Page, Project, ProjectOwned}
import ore.models.user.{User, UserOwned}
import ore.permission.scope.HasScope
import util.syntax.mixins.{ModelSyntax, ZIOSyntax}

package object syntax
    extends HasScope.ToHasScopeOps
    with OrganizationOwned.ToOrganizationOwnedOps
    with ProjectOwned.ToProjectOwnedOps
    with UserOwned.ToUserOwnedOps
    with QueryView.ToQueryFilterableOps
    with Hideable.ToHideableOps
    with Joinable.ToJoinableOps
    with IsMarkdownRenderable.ToIsMarkdownRenderableOps
    with ZIOSyntax {

  implicit def userSyntax(u: User): ModelSyntax.UserSyntax                = new ModelSyntax.UserSyntax(u)
  implicit def userModelRawSyntax(u: Model[User]): ModelSyntax.UserSyntax = new ModelSyntax.UserSyntax(u)
  implicit def userObjSyntax(u: User.type): ModelSyntax.UserObjSyntax     = new ModelSyntax.UserObjSyntax(u)
  implicit def pageObjSyntax(p: Page.type): ModelSyntax.PageObjSyntax     = new ModelSyntax.PageObjSyntax(p)
  implicit def projectSyntax(p: Project): ModelSyntax.ProjectSyntax       = new ModelSyntax.ProjectSyntax(p)
  implicit def orgSyntax(o: Organization): ModelSyntax.OrganizationSyntax = new ModelSyntax.OrganizationSyntax(o)
  implicit def orgModelRawSyntax(o: Model[Organization]): ModelSyntax.OrganizationSyntax =
    new ModelSyntax.OrganizationSyntax(o)
  implicit def authUserSyntax(u: AuthUser): ModelSyntax.AuthUserSyntax = new ModelSyntax.AuthUserSyntax(u)
}
