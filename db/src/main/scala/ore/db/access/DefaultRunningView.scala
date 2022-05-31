package ore.db.access

import scala.language.higherKinds

import ore.db.DbRef

import cats.arrow.FunctionK
import cats.data.OptionT
import slick.dbio.DBIO
import slick.jdbc.JdbcProfile

class DefaultRunningView[F[_], T, M](val profile: JdbcProfile)(
    queryView: ModelView.Later[T, M],
    runAction: FunctionK[DBIO, F]
) extends ModelView[OptionT[F, M], F, T, M] {
  import profile.api._

  private def runSingleInt(rep: Rep[Int]) = runAction(rep.result)

  private def runSingleBool(rep: Rep[Boolean]) = runAction(rep.result)

  override def get(id: DbRef[M]): OptionT[F, M] = OptionT(runAction(queryView.get(id).result.headOption))

  override def one: OptionT[F, M] = OptionT(runAction(queryView.one.result.headOption))

  override def query: Query[T, M, Seq] = queryView.query

  override def size: F[Int] = runSingleInt(queryView.size)

  override def isEmpty: F[Boolean] = runSingleBool(queryView.isEmpty)

  override def nonEmpty: F[Boolean] = runSingleBool(queryView.nonEmpty)

  override def exists(filter: T => Rep[Boolean]): F[Boolean] = runSingleBool(queryView.exists(filter))

  override def forall(filter: T => Rep[Boolean]): F[Boolean] =
    runSingleBool(queryView.forall(filter))

  override def find(filter: T => Rep[Boolean]): OptionT[F, M] =
    OptionT(runAction(queryView.find(filter).result.headOption))

  override def count(predicate: T => Rep[Boolean]): F[Int] = runSingleInt(queryView.count(predicate))

  override def modifyingQuery(
      f: Query[T, M, Seq] => Query[T, M, Seq]
  ): DefaultRunningView[F, T, M] = new DefaultRunningView[F, T, M](profile)(queryView.modifyingQuery(f), runAction)
}
object DefaultRunningView {
  implicit def defaultRunningViewIsQueryView[F[_]]: QueryView[DefaultRunningView[F, *, *]] =
    new QueryView[DefaultRunningView[F, *, *]] {
      override def modifyingView[T, M](fa: DefaultRunningView[F, T, M])(
          f: fa.profile.api.Query[T, M, Seq] => fa.profile.api.Query[T, M, Seq]
      ): DefaultRunningView[F, T, M] = fa.modifyingQuery(f)
    }
}
