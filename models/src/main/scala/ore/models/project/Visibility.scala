package ore.models.project

import scala.collection.immutable

import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.table.common.VisibilityColumn

import enumeratum.values._

sealed abstract class Visibility(
    val value: Int,
    val nameKey: String,
    val showModal: Boolean
) extends IntEnumEntry
object Visibility extends IntEnum[Visibility] {

  val values: immutable.IndexedSeq[Visibility] = findValues

  case object Public        extends Visibility(1, "public", showModal = false)
  case object New           extends Visibility(2, "new", showModal = false)
  case object NeedsChanges  extends Visibility(3, "needsChanges", showModal = true)
  case object NeedsApproval extends Visibility(4, "needsApproval", showModal = false)
  case object SoftDelete    extends Visibility(5, "softDelete", showModal = true)

  def isPublic(visibility: Visibility): Boolean = visibility == Public

  def isPublicFilter[T <: VisibilityColumn[_]]: T => Rep[Boolean] =
    _.visibility === (Public: Visibility)
}
