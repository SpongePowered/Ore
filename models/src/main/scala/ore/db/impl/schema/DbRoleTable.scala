package ore.db.impl.schema

import java.time.OffsetDateTime

import ore.db.impl.OrePostgresDriver.api._
import ore.db.{DbRef, Model, ObjId, ObjOffsetDateTime}
import ore.models.user.role.DbRole
import ore.permission.Permission
import ore.permission.role.RoleCategory

class DbRoleTable(tag: Tag) extends ModelTable[DbRole](tag, "roles") {
  def name         = column[String]("name")
  def category     = column[RoleCategory]("category")
  def permission   = column[Permission]("permission")
  def title        = column[String]("title")
  def color        = column[String]("color")
  def isAssignable = column[Boolean]("is_assignable")
  def rank         = column[Int]("rank")

  override def * = {
    val applyFunc: (
        (Option[DbRef[DbRole]], String, RoleCategory, Permission, String, String, Boolean, Option[Int])
    ) => Model[DbRole] = {
      case (id, name, category, permission, title, color, isAssignable, rank) =>
        Model(
          ObjId.unsafeFromOption(id),
          ObjOffsetDateTime(OffsetDateTime.MIN),
          DbRole(name, category, permission, title, color, isAssignable, rank)
        )
    }

    val unapplyFunc: Model[DbRole] => Option[
      (Option[DbRef[DbRole]], String, RoleCategory, Permission, String, String, Boolean, Option[Int])
    ] = {
      case Model(id, _, DbRole(name, category, permission, title, color, isAssignable, rank)) =>
        Some((id.unsafeToOption, name, category, permission, title, color, isAssignable, rank))
    }

    (id.?, name, category, permission, title, color, isAssignable, rank.?).<>(applyFunc, unapplyFunc)
  }
}
