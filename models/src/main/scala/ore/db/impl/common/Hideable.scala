package ore.db.impl.common

import scala.language.{higherKinds, implicitConversions}

import ore.db.access.{ModelView, QueryView}
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.table.common.VisibilityChangeColumns
import ore.db.{DbRef, Model}
import ore.models.project.Visibility
import ore.models.user.User
import ore.syntax._

/**
  * Represents a model that has a toggleable visibility.
  */
trait Hideable[F[_], M] {
  type MVisibilityChange <: VisibilityChange
  type MVisibilityChangeTable <: VisibilityChangeColumns[MVisibilityChange]

  /**
    * Returns true if the model is visible.
    *
    * @return True if model is visible
    */
  def visibility(m: M): Visibility

  def isDeleted(m: M): Boolean = visibility(m) == Visibility.SoftDelete

  /**
    * Sets whether this project is visible.
    *
    * @param visibility True if visible
    */
  def setVisibility(m: Model[M])(
      visibility: Visibility,
      comment: String,
      creator: DbRef[User]
  ): F[(Model[M], Model[MVisibilityChange])]

  /**
    * Get VisibilityChanges
    */
  def visibilityChanges[V[_, _]: QueryView](m: Model[M])(
      view: V[MVisibilityChangeTable, Model[MVisibilityChange]]
  ): V[MVisibilityChangeTable, Model[MVisibilityChange]]

  def visibilityChangesByDate[V[_, _]: QueryView](m: Model[M])(
      view: V[MVisibilityChangeTable, Model[MVisibilityChange]]
  ): V[MVisibilityChangeTable, Model[MVisibilityChange]] =
    visibilityChanges(m)(view).sortView(_.createdAt)

  def lastVisibilityChange[QOptRet, SRet[_]](m: Model[M])(
      view: ModelView[QOptRet, SRet, MVisibilityChangeTable, Model[MVisibilityChange]]
  ): QOptRet = visibilityChangesByDate(m)(view).filterView(_.resolvedAt.?.isEmpty).one
}
object Hideable {
  type Aux[F[_], M, MChange <: VisibilityChange, MChangeTable <: VisibilityChangeColumns[MChange]] = Hideable[F, M] {
    type MVisibilityChange      = MChange
    type MVisibilityChangeTable = MChangeTable
  }

  class RawOps[M](private val m: M) extends AnyVal {
    def hVisibility[F[_]](implicit hide: Hideable[F, M]): Visibility = hide.visibility(m)

    def isDeleted[F[_]](implicit hide: Hideable[F, M]): Boolean = hide.isDeleted(m)
  }

  class ModelOps[M](private val m: Model[M]) extends AnyVal {

    def hVisibility[F[_]](implicit hide: Hideable[F, M]): Visibility = hide.visibility(m)

    def isDeleted[F[_]](implicit hide: Hideable[F, M]): Boolean = hide.isDeleted(m)

    def setVisibility[F[_]](
        visibility: Visibility,
        comment: String,
        creator: DbRef[User]
    )(implicit hide: Hideable[F, M]): F[(Model[M], Model[hide.MVisibilityChange])] =
      hide.setVisibility(m)(visibility, comment, creator)

    def visibilityChanges[F[_], V[_, _]: QueryView, VCTable <: VisibilityChangeColumns[Change], Change <: VisibilityChange](
        view: V[VCTable, Model[Change]]
    )(implicit hide: Hideable.Aux[F, M, Change, VCTable]): V[VCTable, Model[Change]] = hide.visibilityChanges(m)(view)

    def visibilityChangesByDate[F[_], V[_, _]: QueryView, VCTable <: VisibilityChangeColumns[Change], Change <: VisibilityChange](
        view: V[VCTable, Model[Change]]
    )(implicit hide: Hideable.Aux[F, M, Change, VCTable]): V[VCTable, Model[Change]] =
      hide.visibilityChangesByDate(m)(view)

    def lastVisibilityChange[F[_], QOptRet, SRet[_], VCTable <: VisibilityChangeColumns[Change], Change <: VisibilityChange](
        view: ModelView[QOptRet, SRet, VCTable, Model[Change]]
    )(implicit hide: Hideable.Aux[F, M, Change, VCTable]): QOptRet = hide.lastVisibilityChange(m)(view)

  }

  trait ToHideableOps {
    implicit def hideableToRawOps[M](m: M): Hideable.RawOps[M]            = new Hideable.RawOps(m)
    implicit def hideableToModelOps[M](m: Model[M]): Hideable.ModelOps[M] = new Hideable.ModelOps(m)
  }
}
