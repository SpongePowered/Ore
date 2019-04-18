package ore.models.statistic

import ore.models.user.User
import ore.db.access.ModelView
import ore.db.{DbRef, Model, ModelService}

import cats.data.OptionT
import cats.effect.IO
import com.github.tminglei.slickpg.InetString

/**
  * Represents a statistic entry in a StatTable.
  */
abstract class StatEntry[Subject] {

  /**
    * ID of model the stat is on
    */
  def modelId: DbRef[Subject]

  /**
    * Client address
    */
  def address: InetString

  /**
    * Browser cookie
    */
  def cookie: String

  /**
    * User ID
    */
  def userId: Option[DbRef[User]]

  /**
    * Returns the User associated with this entry, if any.
    *
    * @return User of entry
    */
  def user(implicit service: ModelService): OptionT[IO, Model[User]] =
    OptionT.fromOption[IO](userId).flatMap(ModelView.now(User).get)
}
