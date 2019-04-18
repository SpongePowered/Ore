package ore.db.access

import scala.language.higherKinds

import ore.db._

import cats.Functor
import cats.syntax.all._
import slick.jdbc.JdbcProfile

trait ModelAssociationAccess[Assoc <: OreProfile#AssociativeTable[P, C], P, C, PT <: OreProfile#ModelTable[P], CT <: OreProfile#ModelTable[
  C
], F[_]] {
  import slick.lifted.Query

  def addAssoc(parent: Model[P], child: Model[C]): F[Unit]

  def removeAssoc(parent: Model[P], child: Model[C]): F[Unit]

  def contains(parent: Model[P], child: Model[C]): F[Boolean]

  def deleteAllFromParent(parent: Model[P]): F[Unit]

  def deleteAllFromChild(child: Model[C]): F[Unit]

  def allQueryFromParent(parent: Model[P]): Query[CT, Model[C], Seq]

  def allFromParent(parent: Model[P]): F[Seq[Model[C]]]

  def allQueryFromChild(child: Model[C]): Query[PT, Model[P], Seq]

  def allFromChild(child: Model[C]): F[Seq[Model[P]]]

  def applyChild(child: Model[C]): ChildAssociationAccess[Assoc, P, C, PT, CT, F] =
    new ChildAssociationAccess(child, this)
  def applyParent(parent: Model[P]): ParentAssociationAccess[Assoc, P, C, PT, CT, F] =
    new ParentAssociationAccess(parent, this)
}

class ParentAssociationAccess[Assoc <: OreProfile#AssociativeTable[P, C], P, C, PT <: OreProfile#ModelTable[P], CT <: OreProfile#ModelTable[
  C
], F[_]](
    parent: Model[P],
    val base: ModelAssociationAccess[Assoc, P, C, PT, CT, F]
) {
  import slick.lifted.Query

  def addAssoc(child: Model[C]): F[Unit] = base.addAssoc(parent, child)

  def removeAssoc(child: Model[C]): F[Unit] = base.removeAssoc(parent, child)

  def contains(child: Model[C]): F[Boolean] = base.contains(parent, child)

  def deleteAllFromParent: F[Unit] = base.deleteAllFromParent(parent)

  def allQueryFromParent: Query[CT, Model[C], Seq] = base.allQueryFromParent(parent)

  def allFromParent: F[Seq[Model[C]]] = base.allFromParent(parent)
}

class ChildAssociationAccess[Assoc <: OreProfile#AssociativeTable[P, C], P, C, PT <: OreProfile#ModelTable[P], CT <: OreProfile#ModelTable[
  C
], F[_]](
    child: Model[C],
    val base: ModelAssociationAccess[Assoc, P, C, PT, CT, F]
) {
  import slick.lifted.Query

  def addAssoc(parent: Model[P]): F[Unit] = base.addAssoc(parent, child)

  def removeAssoc(parent: Model[P]): F[Unit] = base.removeAssoc(parent, child)

  def contains(parent: Model[P]): F[Boolean] = base.contains(parent, child)

  def deleteAllFromChild: F[Unit] = base.deleteAllFromChild(child)

  def allQueryFromChild: Query[PT, Model[P], Seq] = base.allQueryFromChild(child)

  def allFromChild: F[Seq[Model[P]]] = base.allFromChild(child)
}

class ModelAssociationAccessImpl[
    Assoc <: OreProfile#AssociativeTable[P, C],
    P,
    C,
    PT <: OreProfile#ModelTable[P],
    CT <: OreProfile#ModelTable[C],
    F[_]: Functor
](val profile: JdbcProfile)(val pCompanion: ModelCompanion.Aux[P, PT], val cCompanion: ModelCompanion.Aux[C, CT])(
    implicit
    query: AssociationQuery[Assoc, P, C],
    service: ModelService[F]
) extends ModelAssociationAccess[Assoc, P, C, PT, CT, F] {
  import profile.api._

  def addAssoc(parent: Model[P], child: Model[C]): F[Unit] =
    service.runDBIO(query.baseQuery += ((parent.id, child.id))).void

  def removeAssoc(parent: Model[P], child: Model[C]): F[Unit] =
    service
      .runDBIO(
        query.baseQuery
          .filter(t => query.parentRef(t) === parent.id.value && query.childRef(t) === child.id.value)
          .delete
      )
      .void

  def contains(parent: Model[P], child: Model[C]): F[Boolean] = service.runDBIO(
    (query.baseQuery
      .filter(t => query.parentRef(t) === parent.id.value && query.childRef(t) === child.id.value)
      .length > 0).result
  )

  override def deleteAllFromParent(parent: Model[P]): F[Unit] =
    service.runDBIO(query.baseQuery.filter(query.parentRef(_) === parent.id.value).delete).void

  override def deleteAllFromChild(child: Model[C]): F[Unit] =
    service.runDBIO(query.baseQuery.filter(query.childRef(_) === child.id.value).delete).void

  override def allQueryFromParent(parent: Model[P]): Query[CT, Model[C], Seq] =
    for {
      assoc <- query.baseQuery if query.parentRef(assoc) === parent.id.value
      child <- cCompanion.baseQuery if query.childRef(assoc) === child.id
    } yield child

  def allFromParent(parent: Model[P]): F[Seq[Model[C]]] = service.runDBIO(allQueryFromParent(parent).result)

  override def allQueryFromChild(child: Model[C]): Query[PT, Model[P], Seq] =
    for {
      assoc  <- query.baseQuery if query.childRef(assoc) === child.id.value
      parent <- pCompanion.baseQuery if query.parentRef(assoc) === parent.id
    } yield parent

  def allFromChild(child: Model[C]): F[Seq[Model[P]]] = service.runDBIO(allQueryFromChild(child).result)
}
