package controllers.apiv2.helpers

import play.api.http.Writeable
import play.api.mvc.Result

import controllers.sugar.Requests.ApiRequest
import models.protocols.APIV2
import ore.data.user.notification.NotificationType
import ore.db.{DbRef, Model, ModelCompanion, ModelQuery, ModelService}
import ore.db.impl.OrePostgresDriver.api._
import ore.models.user.{Notification, User, UserOwned}
import ore.models.user.role.{ProjectUserRole, UserRoleModel}
import ore.permission.Permission
import ore.util.OreMDC
import util.syntax._

import cats.data.NonEmptyList
import cats.syntax.all._
import zio.{IO, UIO, ZIO}
import zio.interop.catz._
import play.api.mvc.Results._

import db.impl.access.UserBase
import ore.db.access.ModelView
import ore.db.impl.common.Named
import ore.db.impl.table.common.RoleTable
import ore.member.MembershipDossier
import ore.models.organization.Organization
import ore.permission.role.Role

import io.circe._
import io.circe.derivation.annotations.SnakeCaseJsonCodec
import io.circe.syntax._

object Members {

  import APIV2.permissionRoleCodec

  @SnakeCaseJsonCodec case class MemberUpdate(
      user: String,
      role: ore.permission.role.Role
  )

  protected def limitOrDefault(limit: Option[Long], default: Long): Long = math.min(limit.getOrElse(default), default)
  protected def offsetOrZero(offset: Long): Long                         = math.max(offset, 0)

  def membersAction(
      getMembersQuery: (Long, Long) => doobie.Query0[APIV2.Member],
      limit: Option[Long],
      offset: Long
  )(
      implicit r: ApiRequest[_, _],
      service: ModelService[UIO],
      writeJson: Writeable[Json]
  ): ZIO[Any, Nothing, Result] = {
    service
      .runDbCon(getMembersQuery(limitOrDefault(limit, 25), offsetOrZero(offset)).to[Vector])
      .map { xs =>
        val users =
          if (r.scopePermission.has(Permission.ManageSubjectMembers)) xs
          else xs.filter(_.role.isAccepted)

        Ok(users.asJson)
      }
  }

  private def check(test: Boolean, errorMessage: String)(implicit writeError: Writeable[ApiError]) =
    if (test) ZIO.fail(BadRequest(ApiError(errorMessage))) else ZIO.unit

  def updateMembers[A <: Named: UserOwned, R <: UserRoleModel[R], RT <: RoleTable[R]](
      getSubject: IO[Result, Model[A]],
      allowOrgMembers: Boolean,
      getMembersQuery: (Long, Long) => doobie.Query0[APIV2.Member],
      createRole: (DbRef[User], DbRef[User], Role) => R,
      roleCompanion: ModelCompanion[R],
      notificationType: NotificationType,
      notificationLocalization: String
  )(
      implicit r: ApiRequest[_, List[MemberUpdate]],
      service: ModelService[UIO],
      users: UserBase[UIO],
      memberships: MembershipDossier.Aux[UIO, A, R, RT],
      modelQuery: ModelQuery[R],
      writeJson: Writeable[Json],
      writeError: Writeable[ApiError]
  ): ZIO[Any, Result, Result] =
    for {
      subject <- getSubject
      resolvedUsers <- ZIO.foreach(r.body) { m =>
        users.withName(m.user)(OreMDC.NoMDC).tupleLeft(m.role).value.someOrFail(NotFound)
      }
      _ <- if (!allowOrgMembers) {
        val existOrgUserRep =
          resolvedUsers.map(_._2.toMaybeOrganization(ModelView.later(Organization))).foldLeft(false: Rep[Boolean]) {
            _ || _.exists
          }

        for {
          existOrgUser <- service.runDBIO(Query(existOrgUserRep).result.head)
          _            <- check(existOrgUser, "Can't add organization as a member")
        } yield ()
      } else ZIO.unit
      currentMembers <- memberships.members(subject)
      resolvedUsersMap  = resolvedUsers.map(t => t._2.id.value -> t._1).toMap
      currentMembersMap = currentMembers.map(t => t.userId -> t).toMap
      newUsers          = resolvedUsersMap.view.filterKeys(!currentMembersMap.contains(_)).toMap
      usersToUpdate = currentMembersMap.collect {
        case (user, oldRole) if resolvedUsersMap.get(user).exists(newRole => oldRole.role != newRole) =>
          (user, (oldRole, resolvedUsersMap(user)))
      }
      usersToDelete = currentMembersMap.view.filterKeys(!resolvedUsersMap.contains(_)).toMap

      permChangesUpdate = usersToUpdate.map(t => t._2._1.role.permissions ++ t._2._2.permissions)
      permChangesDelete = usersToDelete.map(t => t._2.role.permissions)
      permChanges       = Permission((permChangesUpdate ++ permChangesDelete).toSeq: _*)

      _ <- check(usersToUpdate.contains(subject.userId), "Can't update subject")
      _ <- check(usersToDelete.contains(subject.userId), "Can't delete subject")
      // No matter if you're the owner or otherwise, this is the wrong place to set the owner role
      _ <- check(permChanges.has(Permission.IsSubjectOwner), "Can't edit owner roles")
      _ <- check(!r.scopePermission.has(permChanges), "Can't edit roles higher than yourself")
      _ <- service.bulkInsert(newUsers.map(t => createRole(t._1, subject.id, t._2)).toSeq)
      _ <- ZIO.foreach(usersToUpdate.values)(t => service.update(t._1)(_.withRole(t._2)))
      _ <- service.deleteWhere(roleCompanion)(_.id.inSetBind(usersToDelete.values.map(_.id.value)))
      _ <- {
        val notifications = newUsers.map {
          case (userId, role) =>
            Notification(
              userId = userId,
              originId = Some(subject.userId),
              notificationType = notificationType,
              messageArgs = NonEmptyList.of(notificationLocalization, role.title, subject.name)
            )
        }

        service.bulkInsert(notifications.toSeq)
      }
      res <- membersAction(getMembersQuery, None, 0)
    } yield res
}
