package ore.models.organization

import scala.language.higherKinds

import ore.db._
import ore.db.impl.ModelCompanionPartial
import ore.db.impl.common.{Named, Visitable}
import ore.db.impl.schema.{OrganizationRoleTable, OrganizationTable}
import ore.member.{Joinable, MembershipDossier}
import ore.models.user.role.OrganizationUserRole
import ore.models.user.{User, UserOwned}
import ore.permission.role.Role
import ore.permission.scope.HasScope

import cats.syntax.all._
import cats.{Monad, Parallel}
import slick.lifted.TableQuery

/**
  * Represents an Ore Organization. An organization is like a [[User]] in the
  * sense that it shares many qualities with Users and also has a companion
  * User on the forums. An organization is made up by a group of Users who each
  * have a corresponding rank within the organization.
  *
  * @param id             External ID provided by authentication.
  * @param ownerId        The ID of the [[User]] that owns this organization
  */
case class Organization(
    private val id: ObjId[Organization],
    userId: DbRef[User],
    name: String,
    ownerId: DbRef[User]
) extends Named
    with Visitable {
  override def url: String = name
}
object Organization extends ModelCompanionPartial[Organization, OrganizationTable](TableQuery[OrganizationTable]) {
  implicit val orgHasScope: HasScope[Organization] = HasScope.orgScope(_.id.value)

  override def asDbModel(
      model: Organization,
      id: ObjId[Organization],
      time: ObjOffsetDateTime
  ): Model[Organization] = Model(model.id, time, model)

  implicit val query: ModelQuery[Organization] =
    ModelQuery.from(this)

  implicit val isUserOwned: UserOwned[Organization] = (a: Organization) => a.ownerId

  implicit def orgJoinable[F[_]](
      implicit service: ModelService[F],
      F: Monad[F],
      par: Parallel[F]
  ): Joinable.Aux[F, Organization, OrganizationUserRole, OrganizationRoleTable] =
    new Joinable[F, Organization] {
      override type RoleType      = OrganizationUserRole
      override type RoleTypeTable = OrganizationRoleTable

      override def transferOwner(m: Model[Organization])(newOwner: DbRef[User]): F[Model[Organization]] = {
        // Down-grade current owner to "Admin"
        val oldOwner = m.ownerId
        for {
          t2 <- (memberships.getMembership(m)(oldOwner), memberships.getMembership(m)(newOwner)).parTupled
          (roles, memberRoles) = t2
          setOwner <- service.update(m)(_.copy(ownerId = newOwner))
          _ <- roles
            .filter(_.role == Role.OrganizationOwner)
            .toVector
            .parTraverse(role => service.update(role)(_.copy(role = Role.OrganizationAdmin)))
          _ <- memberRoles.toVector.parTraverse(role => service.update(role)(_.copy(role = Role.OrganizationOwner)))
        } yield setOwner
      }

      override def memberships: MembershipDossier.Aux[F, Organization, OrganizationUserRole, OrganizationRoleTable] =
        MembershipDossier.organizationHasMemberships

      override def userOwned: UserOwned[Organization] = isUserOwned
    }
}
