package models.project

import scala.collection.immutable

import db.impl.OrePostgresDriver.api._
import db.impl.table.common.VisibilityChangeColumns

import enumeratum.values._

sealed abstract class Visibility(
    val value: Int,
    val nameKey: String,
    val showModal: Boolean,
    val cssClass: String
) extends IntEnumEntry
object Visibility extends IntEnum[Visibility] {

  val values: immutable.IndexedSeq[Visibility] = findValues

  case object Public        extends Visibility(1, "public", showModal = false, "")
  case object New           extends Visibility(2, "new", showModal = false, "project-new")
  case object NeedsChanges  extends Visibility(3, "needsChanges", showModal = true, "striped project-needsChanges")
  case object NeedsApproval extends Visibility(4, "needsApproval", showModal = false, "striped project-needsChanges")
  case object SoftDelete    extends Visibility(5, "softDelete", showModal = true, "striped project-hidden")

  def isPublic(visibility: Visibility): Boolean = visibility == Public || visibility == New

  def isPublicFilter[T <: VisibilityChangeColumns[_]]: T => Rep[Boolean] =
    vc => vc.visibility === (Public: Visibility) || vc.visibility === (New: Visibility)
}
