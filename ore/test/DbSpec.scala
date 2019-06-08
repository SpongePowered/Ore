import java.util.concurrent.Executors
import javax.sql.DataSource

import scala.concurrent.ExecutionContext

import play.api.db.Databases
import play.api.db.evolutions.Evolutions

import ore.db.impl.query.DoobieOreProtocol

import cats.effect.Effect
import doobie.Transactor
import doobie.scalatest.Checker
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}
import scalaz.zio
import scalaz.zio.{DefaultRuntime, Task}
import scalaz.zio.interop.catz._

trait DbSpec extends FunSuite with Matchers with Checker[Task] with BeforeAndAfterAll with DoobieOreProtocol {

  implicit val runtime: zio.Runtime[Any] = new DefaultRuntime {}

  implicit override def M: Effect[Task] = Effect[Task]

  lazy val database = Databases(
    "org.postgresql.Driver",
    sys.env.getOrElse(
      "ORE_TESTDB_JDBC",
      "jdbc:postgresql://localhost" + sys.env.get("PGPORT").map(":" + _).getOrElse("") + "/" + sys.env
        .getOrElse("DB_DATABASE", "ore_test")
    ),
    config = Map(
      "username" -> sys.env.getOrElse("DB_USERNAME", "ore"),
      "password" -> sys.env.getOrElse("DB_PASSWORD", "")
    )
  )
  private lazy val connectExec  = Executors.newFixedThreadPool(32)
  private lazy val transactExec = Executors.newCachedThreadPool
  private lazy val connectEC    = ExecutionContext.fromExecutor(connectExec)
  private lazy val transactEC   = ExecutionContext.fromExecutor(transactExec)

  lazy val transactor: Transactor.Aux[Task, DataSource] =
    Transactor.fromDataSource[Task](database.dataSource, connectEC, transactEC)

  override def beforeAll(): Unit = Evolutions.applyEvolutions(database)

  override def afterAll(): Unit = {
    database.shutdown()
    connectExec.shutdown()
    transactExec.shutdown()
  }
}
