package models.viewhelper

import scala.language.higherKinds

import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{ProjectRoleTable, ProjectTable}
import ore.db.{DbRef, Model, ModelService}
import ore.models.organization.Organization
import ore.models.project.Project
import ore.models.user.role.{OrganizationUserRole, ProjectUserRole}
import ore.models.user.{User, UserOwned}
import ore.permission.role.RoleCategory
import util.syntax._

import cats.data.OptionT
import cats.syntax.all._
import cats.{MonadError, Parallel}
import slick.lifted.TableQuery

case class OrganizationData(
    joinable: Model[Organization],
    members: Seq[(Model[OrganizationUserRole], Model[User])], // TODO sorted/reverse
    projectRoles: Seq[(Model[ProjectUserRole], Model[Project])]
) extends JoinableData[OrganizationUserRole, Organization] {

  def orga: Model[Organization] = joinable

  def roleCategory: RoleCategory = RoleCategory.Organization

  override def ownerInstance: UserOwned[Organization] = UserOwned[Organization]
}

object OrganizationData {

  def cacheKey(orga: Model[Organization]): String = "organization" + orga.id

  def of[F[_]](orga: Model[Organization])(
      implicit service: ModelService[F],
      F: MonadError[F, Throwable],
      par: Parallel[F]
  ): F[OrganizationData] = {
    for {
      members <- orga.memberships.membersIds(orga)
      members <- members.toVector.traverse { userId =>
        val orgaRole = orga.memberships.getMembership(orga)(userId).map(_.maxBy(_.role.permissions: Long))
        val users =
          ModelView.now(User).get(userId).getOrElseF(F.raiseError(new Exception("Member of organization not found")))
        (orgaRole, users).parTupled
      }
      projectRoles <- service.runDBIO(queryProjectRoles(orga.id).result)
    } yield OrganizationData(orga, members, projectRoles)
  }

  private def queryProjectRoles(userId: DbRef[User]) =
    for {
      (role, project) <- TableQuery[ProjectRoleTable].join(TableQuery[ProjectTable]).on(_.projectId === _.id)
      if role.userId === userId
    } yield (role, project)

  def of[F[_]](orga: Option[Model[Organization]])(
      implicit service: ModelService[F],
      F: MonadError[F, Throwable],
      par: Parallel[F]
  ): OptionT[F, OrganizationData] = OptionT.fromOption[F](orga).semiflatMap(of[F])
}
