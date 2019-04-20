package ore.models.user.notification

import scala.collection.immutable

import ore.db.impl.OrePostgresDriver.api._
import ore.models.user.User
import ore.models.user.role.{OrganizationUserRole, ProjectUserRole, UserRoleModel}
import ore.db.access.ModelView
import ore.db.{Model, ModelService}

import cats.Parallel
import cats.effect.{ContextShift, IO}
import enumeratum.values._

/**
  * A collection of ways to filter invites.
  */
sealed abstract class InviteFilter(
    val value: Int,
    val name: String,
    val title: String,
    val filter: InviteFilter.FilterFunc[?]
) extends IntEnumEntry {
  def apply[F[_]](
      user: Model[User]
  )(implicit service: ModelService[F], cs: ContextShift[F]): F[Seq[Model[UserRoleModel[_]]]] =
    filter(cs)(service)(user)
}

object InviteFilter extends IntEnum[InviteFilter] {
  type FilterFunc[F[_]] = ContextShift[F] => ModelService[F] => Model[User] => F[Seq[Model[UserRoleModel[_]]]]

  val values: immutable.IndexedSeq[InviteFilter] = findValues

  case object All
      extends InviteFilter(
        0,
        "all",
        "notification.invite.all",
        implicit cs =>
          implicit service =>
            user =>
              Parallel.parMap2(
                service.runDBIO(user.projectRoles(ModelView.raw(ProjectUserRole)).filter(!_.isAccepted).result),
                service.runDBIO(
                  user.organizationRoles(ModelView.raw(OrganizationUserRole)).filter(!_.isAccepted).result
                )
              )(_ ++ _)
      )

  case object Projects
      extends InviteFilter(
        1,
        "projects",
        "notification.invite.projects",
        _ =>
          implicit service =>
            user => service.runDBIO(user.projectRoles(ModelView.raw(ProjectUserRole)).filter(!_.isAccepted).result)
      )

  case object Organizations
      extends InviteFilter(
        2,
        "organizations",
        "notification.invite.organizations",
        _ =>
          implicit service =>
            user =>
              service.runDBIO(user.organizationRoles(ModelView.raw(OrganizationUserRole)).filter(!_.isAccepted).result)
      )
}
