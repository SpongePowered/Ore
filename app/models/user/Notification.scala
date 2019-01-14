package models.user

import db.impl.access.UserBase
import db.impl.schema.NotificationTable
import db.{DbRef, InsertFunc, Model, ModelQuery, ObjId, ObjTimestamp}
import ore.user.UserOwned
import ore.user.notification.NotificationType

import cats.data.{NonEmptyList => NEL}
import cats.effect.IO
import slick.lifted.TableQuery

/**
  * Represents a [[User]] notification.
  *
  * @param id               Unique ID
  * @param createdAt        Instant of cretion
  * @param userId           ID of User this notification belongs to
  * @param notificationType Type of notification
  * @param messageArgs      The unlocalized message to display, with the
  *                         parameters to use when localizing
  * @param action           Action to perform on click
  * @param isRead             True if notification has been read
  */
case class Notification private (
    id: ObjId[Notification],
    createdAt: ObjTimestamp,
    userId: DbRef[User],
    originId: DbRef[User],
    notificationType: NotificationType,
    messageArgs: NEL[String],
    action: Option[String],
    isRead: Boolean
) extends Model {

  override type M = Notification
  override type T = NotificationTable

  /**
    * Returns the [[User]] from which this Notification originated from.
    *
    * @return User from which this originated from
    */
  def origin(implicit userBase: UserBase): IO[User] =
    userBase.get(this.originId).getOrElse(throw new NoSuchElementException("Get on None")) // scalafix:ok
}
object Notification {
  def partial(
      userId: DbRef[User],
      originId: DbRef[User],
      notificationType: NotificationType,
      messageArgs: NEL[String],
      action: Option[String] = None,
      isRead: Boolean = false
  ): InsertFunc[Notification] =
    (id, time) => Notification(id, time, userId, originId, notificationType, messageArgs, action, isRead)

  implicit val query: ModelQuery[Notification] =
    ModelQuery.from[Notification](TableQuery[NotificationTable], _.copy(_, _))

  implicit val isUserOwned: UserOwned[Notification] = (a: Notification) => a.userId
}
