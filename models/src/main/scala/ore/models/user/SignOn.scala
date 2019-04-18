package ore.models.user

import ore.db.impl.DefaultModelCompanion
import ore.db.impl.schema.SignOnTable
import ore.db.ModelQuery

import slick.lifted.TableQuery

/**
  * Represents a sign-on instance for a user.
  *
  * @param nonce        Nonce used
  * @param isCompleted  True if sign on was completed
  */
case class SignOn(
    nonce: String,
    isCompleted: Boolean = false
)
object SignOn extends DefaultModelCompanion[SignOn, SignOnTable](TableQuery[SignOnTable]) {

  implicit val query: ModelQuery[SignOn] =
    ModelQuery.from(this)
}
