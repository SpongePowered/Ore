package ore.user

import scala.language.{higherKinds, implicitConversions}

import db.access.{ModelAssociationAccess, ModelAssociationAccessImpl, ModelView}
import db.impl.OrePostgresDriver.api._
import db.impl.schema.{
  OrganizationMembersTable,
  OrganizationRoleTable,
  ProjectMembersTable,
  ProjectRoleTable,
  UserTable
}
import db.impl.table.common.RoleTable
import db.table.AssociativeTable
import db.{AssociationQuery, DbModel, DbModelCompanion, DbRef, ModelQuery, ModelService}
import models.project.Project
import models.user.role.{OrganizationUserRole, ProjectUserRole, UserRoleModel}
import models.user.{Organization, User}
import ore.organization.OrganizationMember
import ore.project.ProjectMember
import util.syntax._

import cats.effect.IO
import cats.syntax.all._

/**
  * Handles and keeps track of [[User]] "memberships" for a model.
  */
trait MembershipDossier[F[_], M] {
  type RoleType <: UserRoleModel[RoleType]
  type RoleTypeTable <: RoleTable[RoleType]
  type MemberType <: Member[RoleType]

  def roles(model: DbModel[M]): ModelView.Now[F, RoleTypeTable, DbModel[RoleType]]

  /**
    * Clears the roles of a User
    *
    * @param user User instance
    */
  def clearRoles(model: DbModel[M], user: DbModel[User]): F[Int]

  /**
    * Constructs a new member object of the MemberType.
    *
    * @param userId User ID of member
    * @return       New Member
    */
  def newMember(model: DbModel[M], userId: DbRef[User]): MemberType

  /**
    * Returns all members of the model. This includes members that have not
    * yet accepted membership.
    *
    * @return All members
    */
  def members(model: DbModel[M]): F[Set[MemberType]]

  /**
    * Adds a new role to the dossier and adds the user as a member if not already.
    *
    * @param role Role to add
    */
  def addRole(model: DbModel[M], userId: DbRef[User], role: RoleType): F[RoleType]

  /**
    * Returns all roles for the specified [[User]].
    *
    * @param user User to get roles for
    * @return     User roles
    */
  def getRoles(model: DbModel[M], user: DbModel[User]): F[Set[DbModel[RoleType]]]

  /**
    * Removes a role from the dossier and removes the member if last role.
    *
    * @param role Role to remove
    */
  def removeRole(model: DbModel[M], role: DbModel[RoleType]): F[Unit]

  /**
    * Clears all user roles and removes the user from the dossier.
    *
    * @param user User to remove
    * @return
    */
  def removeMember(model: DbModel[M], user: DbModel[User]): F[Unit]
}

object MembershipDossier {

  type Aux[F[_], M, RoleType0 <: UserRoleModel[RoleType0], RoleTypeTable0 <: RoleTable[RoleType0], MemberType0 <: Member[
    RoleType0
  ]] = MembershipDossier[F, M] {
    type RoleType      = RoleType0
    type RoleTypeTable = RoleTypeTable0
    type MemberType    = MemberType0
  }

  def apply[F[_], M](
      implicit dossier: MembershipDossier[F, M]
  ): Aux[F, M, dossier.RoleType, dossier.RoleTypeTable, dossier.MemberType] = dossier

  abstract class AbstractMembershipDossier[
      M0,
      RoleType0 <: UserRoleModel[RoleType0]: ModelQuery,
      RoleTypeTable0 <: RoleTable[RoleType0],
      MembersTable <: AssociativeTable[User, M0]
  ](model: DbModelCompanion[M0], roleType: DbModelCompanion.Aux[RoleType0, RoleTypeTable0])(
      childFilter: (RoleTypeTable0, DbModel[M0]) => Rep[Boolean]
  )(
      implicit service: ModelService,
      assocQuery: AssociationQuery[MembersTable, User, M0],
      roleUserOwned: UserOwned[RoleType0]
  ) extends MembershipDossier[IO, M0] {

    type RoleType      = RoleType0
    type RoleTypeTable = RoleTypeTable0

    private def association: ModelAssociationAccess[MembersTable, User, M0, UserTable, model.T, IO] =
      new ModelAssociationAccessImpl(User, model)

    private def addMember(model: DbModel[M0], user: DbModel[User]) =
      association.addAssoc(user, model)

    def roles(model: DbModel[M0]): ModelView.Now[IO, RoleTypeTable, DbModel[RoleType]] =
      ModelView.now(roleType).filterView(childFilter(_, model))

    def members(model: DbModel[M0]): IO[Set[MemberType]] =
      association
        .allFromChild(model)
        .map(_.map(user => newMember(model, user.id)).toSet)

    def addRole(model: DbModel[M0], userId: DbRef[User], role: RoleType): IO[RoleType] = {
      for {
        user   <- ModelView.now(User).get(userId).getOrElseF(IO.raiseError(new Exception("Get on none")))
        exists <- roles(model).exists(_.userId === user.id.value)
        _      <- if (!exists) addMember(model, user) else IO.pure(user)
        ret    <- service.insert(role)
      } yield ret
    }

    def getRoles(model: DbModel[M0], user: DbModel[User]): IO[Set[DbModel[RoleType]]] =
      service.runDBIO(roles(model).filterView(_.userId === user.id.value).query.to[Set].result)

    def removeRole(model: DbModel[M0], role: DbModel[RoleType]): IO[Unit] = {
      for {
        _      <- service.delete(role)
        user   <- role.user
        exists <- roles(model).exists(_.userId === user.id.value)
        _      <- if (!exists) removeMember(model, user) else IO.pure(0)
      } yield ()
    }

    def removeMember(model: DbModel[M0], user: DbModel[User]): IO[Unit] =
      clearRoles(model, user) *> association.removeAssoc(user, model)
  }

  implicit def project(
      implicit service: ModelService
  ): Aux[IO, Project, ProjectUserRole, ProjectRoleTable, ProjectMember] =
    new AbstractMembershipDossier[Project, ProjectUserRole, ProjectRoleTable, ProjectMembersTable](
      Project,
      ProjectUserRole
    )(
      _.projectId === _.id.value
    ) {
      override type MemberType = ProjectMember

      override def newMember(model: DbModel[Project], userId: DbRef[User]): ProjectMember =
        new ProjectMember(model, userId)

      override def clearRoles(model: DbModel[Project], user: DbModel[User]): IO[Int] =
        service.deleteWhere(ProjectUserRole)(s => (s.userId === user.id.value) && (s.projectId === model.id.value))
    }

  implicit def organization(
      implicit service: ModelService
  ): Aux[IO, Organization, OrganizationUserRole, OrganizationRoleTable, OrganizationMember] =
    new AbstractMembershipDossier[Organization, OrganizationUserRole, OrganizationRoleTable, OrganizationMembersTable](
      Organization,
      OrganizationUserRole
    )(
      _.organizationId === _.id.value
    ) {
      override type MemberType = OrganizationMember

      override def newMember(model: DbModel[Organization], userId: DbRef[User]): OrganizationMember =
        new OrganizationMember(model, userId)

      override def clearRoles(model: DbModel[Organization], user: DbModel[User]): IO[Int] =
        service.deleteWhere(OrganizationUserRole)(
          s => (s.userId === user.id.value) && (s.organizationId === model.id.value)
        )
    }

  val STATUS_DECLINE  = "decline"
  val STATUS_ACCEPT   = "accept"
  val STATUS_UNACCEPT = "unaccept"

}
