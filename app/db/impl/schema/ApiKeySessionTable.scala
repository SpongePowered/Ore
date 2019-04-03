package db.impl.schema

import java.sql.Timestamp

import db.DbRef
import db.impl.OrePostgresDriver.api._
import db.table.ModelTable
import models.api.{ApiKey, ApiSession}
import models.user.User

class ApiKeySessionTable(tag: Tag) extends ModelTable[ApiSession](tag, "api_sessions") {
  def token   = column[String]("token")
  def keyId   = column[Option[DbRef[ApiKey]]]("key_id")
  def userId  = column[Option[DbRef[User]]]("user_id")
  def expires = column[Timestamp]("expires")

  override def * =
    (id.?, createdAt.?, (token, keyId, userId, expires)) <> (mkApply((ApiSession.apply _).tupled), mkUnapply(
      ApiSession.unapply
    ))
}
