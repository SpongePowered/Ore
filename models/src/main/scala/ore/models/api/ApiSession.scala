package ore.models.api

import java.time.Instant

import ore.db.impl.DefaultModelCompanion
import ore.db.impl.schema.ApiKeySessionTable
import ore.models.user.User
import ore.db.{DbRef, ModelQuery}

import slick.lifted.TableQuery

case class ApiSession(
    token: String,
    keyId: Option[DbRef[ApiKey]],
    userId: Option[DbRef[User]],
    expires: Instant
)
object ApiSession extends DefaultModelCompanion[ApiSession, ApiKeySessionTable](TableQuery[ApiKeySessionTable]) {
  implicit val query: ModelQuery[ApiSession] = ModelQuery.from(this)
}
