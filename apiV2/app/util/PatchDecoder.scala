package util

import cats.syntax.all._
import io.circe.{ACursor, Decoder}
import perspective._
import perspective.syntax.all._

trait PatchDecoder[A] {

  def decode(cursor: ACursor): Decoder.AccumulatingResult[Option[A]]
}
object PatchDecoder {

  def mkPath[A: Decoder](path: String*): PatchDecoder[A] = (cursor: ACursor) => {
    import cats.instances.either._
    import cats.instances.option._

    val cursorWithPath = path.foldLeft(cursor)(_.downField(_))

    val res = if (cursorWithPath.succeeded) Some(cursorWithPath.as[A]) else None

    res.sequence.toValidatedNel
  }

  def fromName[F[_[_]]: FunctorKC](
      fsd: F[Tuple2K[Const[List[String], *], Decoder, *]]
  )(nameTransform: String => String): F[PatchDecoder] =
    fsd.mapKC(
      λ[Tuple2K[Const[List[String], *], Decoder, *] ~>: PatchDecoder](t => mkPath(t._1.map(nameTransform): _*)(t._2))
    )
}
