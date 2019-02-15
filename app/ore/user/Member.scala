package ore.user

import scala.language.implicitConversions

import db.{DbModel, ModelService}
import models.user.User
import models.user.role.UserRoleModel

import cats.effect.IO

/**
  * Represents a [[User]] member of some entity.
  */
trait Member[RoleType <: UserRoleModel[RoleType]] {

  /**
    * Returns the [[UserRoleModel]]s the user has in this entity.
    *
    * @return Roles user has
    */
  def roles(implicit service: ModelService): IO[Set[DbModel[RoleType]]]

  /**
    * Returns the Member's top role.
    *
    * @return Top role
    */
  def headRole(implicit service: ModelService): IO[DbModel[RoleType]]

}
