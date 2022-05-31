package ore.db.access

import scala.language.higherKinds

import ore.db.{DbRef, Model, ModelCompanion, ModelService}

import cats.arrow.FunctionK
import cats.data.OptionT
import cats.~>
import slick.dbio.DBIO
import slick.jdbc.JdbcProfile
import slick.lifted.{Query, Rep}

trait ModelView[QueryOptRet, SingleRet[_], T, M] {

  /**
    * Returns the model with the specified ID.
    *
    * @param id   ID to lookup
    * @return     Model with ID or None if not found
    */
  def get(id: DbRef[M]): QueryOptRet

  /**
    * Returns a query that gives back the first query
    */
  def one: QueryOptRet

  /**
    * Returns the query equivalent of this access.
    */
  def query: Query[T, M, Seq]

  /**
    * Returns a query for the size of this set.
    *
    * @return Size of set
    */
  def size: SingleRet[Int]

  /**
    * Returns true if this set is empty.
    *
    * @return True if set is empty
    */
  def isEmpty: SingleRet[Boolean]

  /**
    * Returns true if this set is not empty.
    *
    * @return True if not empty
    */
  def nonEmpty: SingleRet[Boolean]

  /**
    * Returns true if any models match the specified filter.
    *
    * @param filter Filter to use
    * @return       True if any model matches
    */
  def exists(filter: T => Rep[Boolean]): SingleRet[Boolean]

  /**
    * Returns true if all models match the specified filter.
    *
    * @param filter Filter to use
    * @return       True if any model matches
    */
  def forall(filter: T => Rep[Boolean]): SingleRet[Boolean]

  /**
    * Returns the first model matching the specified filter.
    *
    * @param filter Filter to use
    * @return       Model matching filter, if any
    */
  def find(filter: T => Rep[Boolean]): QueryOptRet

  /**
    * Modifies this [[ModelView]] by working on the underlying query.
    */
  def modifyingQuery(f: Query[T, M, Seq] => Query[T, M, Seq]): ModelView[QueryOptRet, SingleRet, T, M]

  /**
    * Constructs a new view based in this on, by filtering out some values.
    * @param filter The filter to apply.
    */
  def filterView(filter: T => Rep[Boolean]): ModelView[QueryOptRet, SingleRet, T, M] =
    modifyingQuery(_.filter(filter))

  /**
    * Counts how many elements in this set fulfill some predicate.
    * @param predicate The predicate to use
    * @return The amount of elements that fulfill the predicate.
    */
  def count(predicate: T => Rep[Boolean]): SingleRet[Int]
}
object ModelView {
  type Now[F[_], T, M] = ModelView[OptionT[F, M], F, T, M]
  type Later[T, M]     = ModelView[Query[T, M, Seq], Rep, T, M]
  type Raw[T, M]       = Query[T, M, Seq]

  implicit def modelViewIsQueryView[QueryOptRet, SingleRet[_]]: QueryView[ModelView[QueryOptRet, SingleRet, *, *]] =
    new QueryView[ModelView[QueryOptRet, SingleRet, *, *]] {
      override def modifyingView[T, M](fa: ModelView[QueryOptRet, SingleRet, T, M])(
          f: Query[T, M, Seq] => Query[T, M, Seq]
      ): ModelView[QueryOptRet, SingleRet, T, M] = fa.modifyingQuery(f)
    }

  def now[M, F[_]](model: ModelCompanion[M])(
      implicit service: ModelService[F]
  ): ModelView[OptionT[F, Model[M]], F, model.T, Model[M]] =
    defaultNowView(model.profile)(model.baseQuery, _.id, FunctionK.lift(service.runDBIO))

  def later[M](model: ModelCompanion[M]): ModelView[Query[model.T, Model[M], Seq], Rep, model.T, Model[M]] =
    defaultLaterView(model.profile)(model.baseQuery, _.id)

  def raw[M](model: ModelCompanion[M]): Raw[model.T, Model[M]] = model.baseQuery

  def defaultLaterView[T, M](profile: JdbcProfile)(
      baseQuery: Query[T, M, Seq],
      idRef: T => Rep[DbRef[M]]
  ): ModelView[Query[T, M, Seq], Rep, T, M] = new DefaultQueryView(profile)(baseQuery, idRef)

  def defaultNowView[F[_], T, M](profile: JdbcProfile)(
      baseQuery: Query[T, M, Seq],
      idRef: T => Rep[DbRef[M]],
      runAction: DBIO ~> F
  ): ModelView[OptionT[F, M], F, T, M] =
    defaultNowViewUsingLater[F, T, M](profile)(defaultLaterView(profile)(baseQuery, idRef), runAction)

  def defaultNowViewUsingLater[F[_], T, M](profile: JdbcProfile)(
      queryView: Later[T, M],
      runAction: DBIO ~> F
  ): ModelView[OptionT[F, M], F, T, M] =
    new DefaultRunningView[F, T, M](profile)(queryView, runAction)
}
