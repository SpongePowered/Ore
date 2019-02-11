package models.user

import db.access.ModelView
import db.impl.schema.NotificationTable
import db.{DbModel, DbRef, DefaultDbModelCompanion, ModelQuery, ModelService}
import ore.user.UserOwned
import ore.user.notification.NotificationType

import cats.data.{NonEmptyList => NEL}
import cats.effect.IO
import slick.lifted.TableQuery

/**
  * Represents a [[User]] notification.
  *
  * @param userId           ID of User this notification belongs to
  * @param notificationType Type of notification
  * @param messageArgs      The unlocalized message to display, with the
  *                         parameters to use when localizing
  * @param action           Action to perform on click
  * @param isRead             True if notification has been read
  */
case class Notification private (
    userId: DbRef[User],
    originId: DbRef[User],
    notificationType: NotificationType,
    messageArgs: NEL[String],
    action: Option[String] = None,
    isRead: Boolean = false
) {

  /**
    * Returns the [[User]] from which this Notification originated from.
    *
    * @return User from which this originated from
    */
  def origin(implicit service: ModelService): IO[DbModel[User]] =
    ModelView.now(User).get(this.originId).getOrElseF(IO.raiseError(new NoSuchElementException("Get on None")))
}
object Notification extends DefaultDbModelCompanion[Notification, NotificationTable](TableQuery[NotificationTable]) {

  implicit val query: ModelQuery[Notification] =
    ModelQuery.from(this)

  implicit val isUserOwned: UserOwned[Notification] = (a: Notification) => a.userId
}
