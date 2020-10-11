package ore.db.impl.schema

import ore.db.DbRef
import ore.db.impl.OrePostgresDriver.api._
import ore.models.api.ApiKey
import ore.models.user.User
import ore.permission.Permission

class ApiKeyTable(tag: Tag) extends ModelTable[ApiKey](tag, "api_keys") {
  def name              = column[String]("name")
  def ownerId           = column[DbRef[User]]("owner_id")
  def tokenIdentifier   = column[String]("token_identifier")
  def rawKeyPermissions = column[Permission]("raw_key_permissions")

  override def * =
    (id.?, createdAt.?, (name, ownerId, tokenIdentifier, rawKeyPermissions)).<>(
      mkApply((ApiKey.apply _).tupled),
      mkUnapply(
        ApiKey.unapply
      )
    )
}
