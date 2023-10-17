package ore.models.user.role

import scala.language.higherKinds

import ore.db.impl.common.Visitable
import ore.db.{DbRef, Model, ModelService}
import ore.models.organization.Organization
import ore.models.user.User
import ore.permission.role.Role

import cats.MonadError

/**
  * Represents a user's [[Role]] in something like a [[ore.models.project.Project]] or
  * [[Organization]].
  */
abstract class UserRoleModel[Self] {

  def userId: DbRef[User]

  /**
    * Type of Role
    */
  def role: Role

  /**
    * True if has been accepted
    */
  def isAccepted: Boolean

  /**
    * Returns the subject of this Role.
    *
    * @return Subject of Role
    */
  def subject[F[_]: ModelService](implicit F: MonadError[F, Throwable]): F[Model[Visitable]]

  def withRole(role: Role): Self

  def withAccepted(accepted: Boolean): Self
}
