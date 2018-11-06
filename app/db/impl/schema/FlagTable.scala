package db.impl.schema

import java.sql.Timestamp

import db.DbRef
import db.impl.OrePostgresDriver.api._
import db.table.ModelTable
import models.project.{Flag, Project}
import models.user.User
import ore.project.FlagReason

class FlagTable(tag: Tag) extends ModelTable[Flag](tag, "project_flags") {

  def projectId  = column[DbRef[Project]]("project_id")
  def userId     = column[DbRef[User]]("user_id")
  def reason     = column[FlagReason]("reason")
  def comment    = column[String]("comment")
  def isResolved = column[Boolean]("is_resolved")
  def resolvedAt = column[Timestamp]("resolved_at")
  def resolvedBy = column[DbRef[User]]("resolved_by")

  override def * =
    mkProj((id.?, createdAt.?, projectId, userId, reason, comment, isResolved, resolvedAt.?, resolvedBy.?))(
      mkTuple[Flag]()
    )
}
