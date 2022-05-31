package ore.db.access

import scala.language.{higherKinds, implicitConversions}

import slick.lifted.{Query, Rep}

trait QueryView[F[_, _]] {

  def modifyingView[T, M](fa: F[T, M])(f: Query[T, M, Seq] => Query[T, M, Seq]): F[T, M]

  def filterView[T, M](fa: F[T, M])(f: T => Rep[Boolean]): F[T, M] = modifyingView(fa)(_.filter(f))

  def sortView[T, M, OrdT](fa: F[T, M])(f: T => OrdT)(implicit ev: OrdT => slick.lifted.Ordered): F[T, M] =
    modifyingView(fa)(_.sortBy(f))
}
object QueryView {

  implicit val queryIsQueryView: QueryView[Query[*, *, Seq]] = new QueryView[Query[*, *, Seq]] {
    override def modifyingView[T, M](fa: Query[T, M, Seq])(
        f: Query[T, M, Seq] => Query[T, M, Seq]
    ): Query[T, M, Seq] = f(fa)
  }

  class QueryViewOps[F[_, _], T, M](private val fa: F[T, M]) extends AnyVal {
    def filterView(f: T => Rep[Boolean])(implicit tc: QueryView[F]): F[T, M] = tc.filterView(fa)(f)

    def sortView[TOrd](f: T => TOrd)(implicit tc: QueryView[F], ev: TOrd => slick.lifted.Ordered): F[T, M] =
      tc.sortView(fa)(f)

    def modifyView(f: Query[T, M, Seq] => Query[T, M, Seq])(implicit tc: QueryView[F]): F[T, M] =
      tc.modifyingView(fa)(f)
  }

  trait ToQueryFilterableOps {
    implicit def toOps[F[_, _], T, M](fa: F[T, M]): QueryViewOps[F, T, M] = new QueryViewOps[F, T, M](fa)
  }
}
