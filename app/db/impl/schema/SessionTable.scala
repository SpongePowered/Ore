package db.impl.schema

import java.sql.Timestamp

import db.impl.OrePostgresDriver.api._
import db.table.ModelTable
import models.user.{Session => DbSession}

class SessionTable(tag: Tag) extends ModelTable[DbSession](tag, "user_sessions") {

  def expiration = column[Timestamp]("expiration")
  def username   = column[String]("username")
  def token      = column[String]("token")

  def * = mkProj((id.?, createdAt.?, expiration, username, token))(mkTuple[DbSession]())
}
