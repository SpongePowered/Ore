package db.impl.schema

import java.sql.Timestamp

import db.{DbRef, ObjId, ObjectTimestamp}
import db.impl.OrePostgresDriver.api._
import db.table.ModelTable
import models.user.{LoggedAction, LoggedActionContext, LoggedActionModel, User}

import com.github.tminglei.slickpg.InetString

class LoggedActionTable[Ctx](tag: Tag) extends ModelTable[LoggedActionModel[Ctx]](tag, "logged_actions") {

  def userId          = column[DbRef[User]]("user_id")
  def address         = column[InetString]("address")
  def action          = column[LoggedAction[Ctx]]("action")
  def actionContext   = column[LoggedActionContext[Ctx]]("action_context")
  def actionContextId = column[DbRef[Ctx]]("action_context_id")
  def newState        = column[String]("new_state")
  def oldState        = column[String]("old_state")

  private def rawApply(
      id: Option[DbRef[LoggedActionModel[Ctx]]],
      createdAt: Option[Timestamp],
      userId: DbRef[User],
      address: InetString,
      action: LoggedAction[Ctx],
      actionContext: LoggedActionContext[Ctx],
      actionContextId: DbRef[Ctx],
      newState: String,
      oldState: String
  ) =
    LoggedActionModel(
      ObjId.unsafeFromOption(id),
      ObjectTimestamp.unsafeFromOption(createdAt),
      userId,
      address,
      action,
      actionContext,
      actionContextId,
      newState,
      oldState
    )

  private def rawUnapply(m: LoggedActionModel[Ctx]) = m match {
    case LoggedActionModel(
        id,
        createdAt,
        userId,
        address,
        action,
        actionContext,
        actionContextId,
        newState,
        oldState
        ) =>
      Some(
        (
          id.unsafeToOption,
          createdAt.unsafeToOption,
          userId,
          address,
          action,
          actionContext,
          actionContextId,
          newState,
          oldState
        )
      )
    case _ => None
  }

  override def * =
    (
      id.?,
      createdAt.?,
      userId,
      address,
      action,
      actionContext,
      actionContextId,
      newState,
      oldState
    ) <> ((rawApply _).tupled, rawUnapply _)
}
