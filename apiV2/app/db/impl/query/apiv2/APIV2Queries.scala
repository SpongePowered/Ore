package db.impl.query.apiv2

import models.protocols.APIV2
import models.querymodels._
import ore.db.DbRef
import ore.db.impl.query.DoobieOreProtocol
import ore.models.user.User

import cats.Reducible
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.implicits.javasql._
import doobie.implicits.javatime.JavaTimeLocalDateMeta
import doobie.util.Put
import doobie.util.fragment.Elem
import perspective._
import perspective.syntax.all._

trait APIV2Queries extends DoobieOreProtocol {

  //Like in, but takes a tuple
  def in2[F[_]: Reducible, A: Put, B: Put](f: Fragment, fs: F[(A, B)]): Fragment =
    fs.toList.map { case (a, b) => fr0"($a, $b)" }.foldSmash1(f ++ fr0"IN (", fr",", fr")")

  def array[F[_]: Reducible, A: Put](fs: F[A]): Fragment =
    fs.toList.map(a => fr0"$a").foldSmash1(fr0"ARRAY[", fr",", fr0"]")

  def array2Text[F[_]: Reducible, A: Put, B: Put](t1: String, t2: String)(fs: F[(A, B)]): Fragment =
    fs.toList
      .map { case (a, b) => fr0"($a::" ++ Fragment.const(t1) ++ fr0", $b::" ++ Fragment.const(t2) ++ fr0")::TEXT" }
      .foldSmash1(fr0"ARRAY[", fr",", fr0"]")

  case class Column[A](name: String, mkElem: A => Elem)
  object Column {
    def arg[A](name: String)(implicit put: Put[A]): Column[A]         = Column(name, Elem.Arg(_, put))
    def opt[A](name: String)(implicit put: Put[A]): Column[Option[A]] = Column(name, Elem.Opt(_, put))
  }

  protected def updateTable[F[_[_]]: ApplicativeKC: FoldableKC](
      table: String,
      columns: F[Column],
      edits: F[Option]
  ): Fragment = {

    val applyUpdate = new FunctionK[Tuple2K[Option, Column, *], Compose2[Option, Const[Fragment, *], *]] {
      override def apply[A](tuple: Tuple2K[Option, Column, A]): Option[Fragment] = {
        val column = tuple._2
        tuple._1.map(value => Fragment.const(column.name) ++ Fragment("= ?", List(column.mkElem(value))))
      }
    }

    val updatesSeq = edits
      .map2KC(columns)(applyUpdate)
      .foldMapKC[List[Option[Fragment]]](
        λ[Compose2[Option, Const[Fragment, *], *] ~>: Compose3[List, Option, Const[Fragment, *], *]](List(_))
      )

    val updates = Fragments.setOpt(updatesSeq: _*)

    sql"""UPDATE """ ++ Fragment.const(table) ++ updates
  }

  def countOfSelect(select: Fragment): doobie.Query0[Long] =
    (sql"SELECT COUNT(*) FROM " ++ Fragments.parentheses(select) ++ fr"sq").query[Long]

  def visibilityFrag(canSeeHidden: Boolean, currentUserId: Option[DbRef[User]], table: Fragment): Option[Fragment] = {
    Option.when(!canSeeHidden) {
      currentUserId.fold(fr"($table.visibility = 1 OR $table.visibility = 2)") { id =>
        fr"($table.visibility = 1 OR $table.visibility = 2 OR ($id IN (SELECT pm.user_id FROM project_members_all pm WHERE pm.id = p.id) AND $table.visibility != 5))"
      }
    }
  }
}
