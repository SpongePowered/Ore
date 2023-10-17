package ore.db.impl.service

import ore.db.impl.OrePostgresDriver
import ore.db.{Model, ModelCompanion, ModelQuery, ModelService}

import cats.effect.{Clock, Sync}
import doobie.syntax.all._
import doobie.{ConnectionIO, Transactor}
import slick.dbio.DBIO
import slick.lifted.Rep
import zio.interop.catz._
import zio.{Task, ZIO}

class OreModelService(
    db: OrePostgresDriver.backend.DatabaseDef,
    transactor: Transactor[OreModelService.F]
) extends ModelService[OreModelService.F] {
  import OreModelService.F

  implicit val clock: Clock[F] = Clock.create[F]

  override def runDBIO[R](action: DBIO[R]): F[R] = ZIO.fromFuture(_ => db.run(action))

  override def runDbCon[R](program: ConnectionIO[R]): F[R] = program.transact(transactor)

  override def insertRaw[M](companion: ModelCompanion[M])(model: M): F[Model[M]] =
    companion.insert[F](model).flatMap(runDBIO)

  override def bulkInsert[M](models: Seq[M])(implicit query: ModelQuery[M]): F[Seq[Model[M]]] =
    query.companion.bulkInsert[F](models).flatMap(runDBIO)

  override def updateRaw[M](companion: ModelCompanion[M])(model: Model[M])(update: M => M): F[Model[M]] =
    companion.update[F](model)(update).flatMap(runDBIO)

  override def delete[M](model: Model[M])(implicit query: ModelQuery[M]): F[Int] =
    runDBIO(query.companion.delete(model))

  override def deleteWhere[M](model: ModelCompanion[M])(filter: model.T => Rep[Boolean]): F[Int] =
    runDBIO(model.deleteWhere(filter))
}
object OreModelService {
  type F[A] = Task[A]
  val F: Sync[F] = Sync[F]
}
