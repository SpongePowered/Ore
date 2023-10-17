package ore.member

import scala.language.{higherKinds, implicitConversions}

import ore.db._
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema._
import ore.db.impl.table.common.RoleTable
import ore.models.organization.Organization
import ore.models.project.Project
import ore.models.user.User
import ore.models.user.role.{OrganizationUserRole, ProjectUserRole, UserRoleModel}

import cats.Monad
import cats.syntax.all._

/**
  * Handles and keeps track of [[User]] "memberships" for a model.
  */
trait MembershipDossier[F[_], M] {
  type RoleType <: UserRoleModel[RoleType]
  type RoleTypeTable <: RoleTable[RoleType]

  def memberships(model: Model[M]): ModelView.Now[F, RoleTypeTable, Model[RoleType]]

  /**
    * Returns the ids all members of the model. This includes members that have not
    * yet accepted membership.
    *
    * @return All members
    */
  def membersIds(model: Model[M]): F[Set[DbRef[User]]]

  /**
    * Returns all members of the model. This includes members that have not
    * yet accepted membership.
    *
    * @return All members
    */
  def members(model: Model[M]): F[Seq[Model[RoleType]]]

  /**
    * Adds a new role to the dossier and adds the user as a member if not already.
    *
    * @param role Role to add
    */
  def setRole(model: Model[M])(userId: DbRef[User], role: RoleType): F[RoleType]

  /**
    * Returns membership for the specified [[User]].
    *
    * @param user User to get roles for
    * @return     User roles
    */
  def getMembership(model: Model[M])(user: DbRef[User]): F[Option[Model[RoleType]]]

  /**
    * Clears all user roles and removes the user from the dossier.
    *
    * @param user User to remove
    * @return
    */
  def removeMember(model: Model[M])(user: DbRef[User]): F[Unit]
}

object MembershipDossier {

  type Aux[F[_], M, RoleType0 <: UserRoleModel[RoleType0], RoleTypeTable0 <: RoleTable[RoleType0]] =
    MembershipDossier[F, M] {
      type RoleType      = RoleType0
      type RoleTypeTable = RoleTypeTable0
    }

  def apply[F[_], M](
      implicit dossier: MembershipDossier[F, M]
  ): Aux[F, M, dossier.RoleType, dossier.RoleTypeTable] = dossier

  abstract class AbstractMembershipDossier[F[_], M, RoleType0 <: UserRoleModel[RoleType0], RoleTypeTable0 <: RoleTable[
    RoleType0
  ]](
      RoleType: ModelCompanion.Aux[RoleType0, RoleTypeTable0]
  )(implicit service: ModelService[F], F: Monad[F])
      extends MembershipDossier[F, M] {
    override type RoleType      = RoleType0
    override type RoleTypeTable = RoleTypeTable0

    override def membersIds(model: Model[M]): F[Set[DbRef[User]]] =
      service.runDBIO(memberships(model).query.map(_.userId).to[Set].result)

    override def members(model: Model[M]): F[Seq[Model[RoleType]]] =
      service.runDBIO(memberships(model).query.to[Seq].result)

    override def setRole(model: Model[M])(userId: DbRef[User], role: RoleType): F[RoleType] =
      for {
        exists <- memberships(model).exists(_.userId === userId)
        _      <- if (exists) removeMember(model)(userId) else F.unit
        ret    <- service.insertRaw(RoleType)(role)
      } yield ret

    override def getMembership(model: Model[M])(user: DbRef[User]): F[Option[Model[RoleType]]] =
      memberships(model).find(_.userId === user).value

    override def removeMember(model: Model[M])(user: DbRef[User]): F[Unit] =
      service.runDBIO(memberships(model).query.filter(_.userId === user).delete).void
  }

  implicit def projectHasMemberships[F[_]](
      implicit service: ModelService[F],
      F: Monad[F]
  ): MembershipDossier.Aux[F, Project, ProjectUserRole, ProjectRoleTable] =
    new AbstractMembershipDossier[F, Project, ProjectUserRole, ProjectRoleTable](
      ProjectUserRole
    ) {

      override def memberships(model: Model[Project]): ModelView.Now[F, ProjectRoleTable, Model[ProjectUserRole]] =
        ModelView.now(ProjectUserRole).filterView(_.projectId === model.id.value)
    }

  implicit def organizationHasMemberships[F[_]](
      implicit service: ModelService[F],
      F: Monad[F]
  ): MembershipDossier.Aux[F, Organization, OrganizationUserRole, OrganizationRoleTable] =
    new AbstractMembershipDossier[
      F,
      Organization,
      OrganizationUserRole,
      OrganizationRoleTable,
    ](
      OrganizationUserRole
    ) {

      override def memberships(
          model: Model[Organization]
      ): ModelView.Now[F, OrganizationRoleTable, Model[OrganizationUserRole]] =
        ModelView.now(OrganizationUserRole).filterView(_.organizationId === model.id.value)
    }

  val STATUS_DECLINE  = "decline"
  val STATUS_ACCEPT   = "accept"
  val STATUS_UNACCEPT = "unaccept"
}
