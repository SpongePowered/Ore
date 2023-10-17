package ore.models.user

import java.time.OffsetDateTime

import ore.db.impl.DefaultModelCompanion
import ore.db.impl.common.Expirable
import ore.db.impl.schema.SessionTable
import ore.db.{DbRef, ModelQuery}

import slick.lifted.TableQuery

/**
  * Represents a persistant [[User]] session.
  *
  * @param expiration Instant of expiration
  * @param userId     Id of user session belongs to
  * @param token      Unique token
  */
case class Session(
    expiration: OffsetDateTime,
    userId: DbRef[User],
    token: String
) extends Expirable
object Session extends DefaultModelCompanion[Session, SessionTable](TableQuery[SessionTable]) {

  implicit val query: ModelQuery[Session] =
    ModelQuery.from(this)
}
