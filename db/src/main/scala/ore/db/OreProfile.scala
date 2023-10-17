package ore.db

import scala.language.implicitConversions

import java.time.OffsetDateTime

import slick.jdbc.JdbcProfile

trait OreProfile extends JdbcProfile {

  /**
    * Represents a Table in the database that contains [[Model]]s.
    */
  abstract class ModelTable[M](tag: api.Tag, name: String) extends Table[Model[M]](tag, name) {
    import api._

    /** The Model's primary key column */
    def id = column[DbRef[M]]("id", O.PrimaryKey, O.AutoInc)

    /** The [[java.time.Instant]] of when a Model was created. */
    def createdAt = column[OffsetDateTime]("created_at")
  }

  /**
    * Represents a associative table between two models.
    *
    * @param tag Table tag
    * @param name Table name
    */
  abstract class AssociativeTable[A, B](
      tag: api.Tag,
      name: String
  ) extends Table[(DbRef[A], DbRef[B])](tag, name)

  /**
    * A wrapper class for a T => Rep[Boolean] on a ModelTable. This allows for easier
    * chaining of filters on a ModelTable. ModelFilters can have their base
    * function lifted out of this wrapper implicitly.
    *
    * @param fn   Base filter function
    */
  class ModelFilter[T](private val fn: T => api.Rep[Boolean]) {
    import api._

    /**
      * Applies && to the wrapped function and returns a new filter.
      *
      * @param that Filter function to apply
      * @return New model filter
      */
    def &&(that: T => Rep[Boolean]): T => Rep[Boolean] = m => fn(m) && that(m)

    /**
      * Applies || to the wrapped function and returns a new filter.
      *
      * @param that Filter function to apply
      * @return New filter
      */
    def ||(that: T => Rep[Boolean]): T => Rep[Boolean] = m => fn(m) || that(m)

    /**
      * Applies ! to the wrapped function and returns a new filter.
      * @return New filter
      */
    def unary_! : T => Rep[Boolean] = m => !fn(m)
  }
  object ModelFilter {
    def apply[M](model: ModelCompanion[M])(f: model.T => api.Rep[Boolean]): model.T => api.Rep[Boolean] = f
  }

  implicit def liftFilter[T](f: T => api.Rep[Boolean]): ModelFilter[T] = new ModelFilter(f)
}
