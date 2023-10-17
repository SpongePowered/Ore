package ore.db.impl.schema

import java.time.OffsetDateTime
import java.util.Locale

import ore.data.Prompt
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.table.common.NameColumn
import ore.db.{DbRef, Model, ObjId, ObjOffsetDateTime}
import ore.models.user.User

class UserTable(tag: Tag) extends ModelTable[User](tag, "users") with NameColumn[User] {

  // Override to remove auto increment
  override def id = column[DbRef[User]]("id", O.PrimaryKey)

  def fullName    = column[String]("full_name")
  def email       = column[String]("email")
  def tagline     = column[String]("tagline")
  def joinDate    = column[OffsetDateTime]("join_date")
  def readPrompts = column[List[Prompt]]("read_prompts")
  def lang        = column[Locale]("language")

  override def * = {
    val applyFunc: (
        (
            Option[DbRef[User]],
            Option[OffsetDateTime],
            Option[String],
            String,
            Option[String],
            Option[String],
            Option[OffsetDateTime],
            List[Prompt],
            Option[Locale]
        )
    ) => Model[User] = {
      case (id, time, fullName, name, email, tagline, joinDate, prompts, lang) =>
        Model(
          ObjId.unsafeFromOption(id),
          ObjOffsetDateTime.unsafeFromOption(time),
          User(
            ObjId.unsafeFromOption(id),
            fullName,
            name,
            email,
            tagline,
            joinDate,
            prompts,
            lang
          )
        )
    }

    val unapplyFunc: Model[User] => Option[
      (
          Option[DbRef[User]],
          Option[OffsetDateTime],
          Option[String],
          String,
          Option[String],
          Option[String],
          Option[OffsetDateTime],
          List[Prompt],
          Option[Locale]
      )
    ] = {
      case Model(
          _,
          createdAt,
          User(
            id,
            fullName,
            name,
            email,
            tagline,
            joinDate,
            readPrompts,
            lang
          )
          ) =>
        Option(
          (
            id.unsafeToOption,
            createdAt.unsafeToOption,
            fullName,
            name,
            email,
            tagline,
            joinDate,
            readPrompts,
            lang
          )
        )
    }

    (
      id.?,
      createdAt.?,
      fullName.?,
      name,
      email.?,
      tagline.?,
      joinDate.?,
      readPrompts,
      lang.?
    ).<>(applyFunc, unapplyFunc)
  }
}
