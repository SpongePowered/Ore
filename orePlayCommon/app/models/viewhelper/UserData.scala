package models.viewhelper

import scala.language.higherKinds

import controllers.sugar.Requests.OreRequest
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{OrganizationRoleTable, OrganizationTable, UserTable}
import ore.db.{Model, ModelService}
import ore.models.organization.Organization
import ore.models.project.Project
import ore.models.user.User
import ore.models.user.role.OrganizationUserRole
import ore.permission._
import ore.permission.role.Role
import ore.permission.scope.GlobalScope

import cats.syntax.all._
import cats.{Monad, Parallel}
import slick.lifted.TableQuery

// TODO separate Scoped UserData

case class UserData(
    headerData: HeaderData,
    user: Model[User],
    isOrga: Boolean,
    projectCount: Int,
    orgas: Seq[(Model[Organization], Model[User], Model[OrganizationUserRole], Model[User])],
    globalRoles: Set[Role],
    userPerm: Permission
) {

  def global: HeaderData = headerData

  def hasUser: Boolean                 = global.hasUser
  def currentUser: Option[Model[User]] = global.currentUser

  def isCurrent: Boolean = currentUser.contains(user)
}

object UserData {

  private def queryRoles(user: Model[User]) =
    for {
      role    <- TableQuery[OrganizationRoleTable] if role.userId === user.id.value
      org     <- TableQuery[OrganizationTable] if role.organizationId === org.id
      orgUser <- TableQuery[UserTable] if org.id === orgUser.id
      owner   <- TableQuery[UserTable] if org.ownerId === owner.id
    } yield (org, orgUser, role, owner)

  def of[F[_]](request: OreRequest[_], user: Model[User])(
      implicit service: ModelService[F],
      F: Monad[F],
      par: Parallel[F]
  ): F[UserData] =
    for {
      isOrga       <- user.toMaybeOrganization(ModelView.now(Organization)).isDefined
      projectCount <- user.projects(ModelView.now(Project)).size
      t            <- perms(user)
      (globalRoles, userPerms) = t
      orgas <- service.runDBIO(queryRoles(user).result)
    } yield UserData(request.headerData, user, isOrga, projectCount, orgas, globalRoles, userPerms)

  def perms[F[_]](user: Model[User])(
      implicit service: ModelService[F],
      F: Monad[F],
      par: Parallel[F]
  ): F[(Set[Role], Permission)] =
    (
      user.permissionsIn(GlobalScope),
      user.globalRoles.allFromParent
    ).parMapN((userPerms, globalRoles) => (globalRoles.map(_.toRole).toSet, userPerms))
}
