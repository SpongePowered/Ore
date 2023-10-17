package util

import cats.data.{Validated, ValidatedNel}
import cats.syntax.all._
import io.circe.Decoder.AccumulatingResult
import io.circe.{Decoder, HCursor}
import perspective._
import perspective.syntax.all._

object PartialUtils {
  type PatchResult[A]    = Decoder.AccumulatingResult[Option[A]]
  type ValidateResult[A] = ValidatedNel[String, Option[A]]

  type Validator[A] = A => ValidatedNel[String, A]
  object Validator {
    def noValidation[A](value: A): ValidatedNel[String, A] = Validated.valid(value)

    def invaidIfEmpty(field: String): Validator[Option[String]] = {
      case None        => Validated.valid(None)
      case Some("")    => Validated.invalidNel(s"Passed empty string to $field")
      case Some(value) => Validated.valid(Some(value))
    }

    def validIfEmpty[A](validator: Validator[A]): Validator[Option[A]] = {
      case None    => Validated.valid(None)
      case Some(v) => validator(v).map(Some.apply)
    }

    def checkLength(field: String, maxLength: Int): Validator[String] =
      name => Validated.condNel(name.length <= maxLength, name, s"${field.capitalize} too long. Max length $maxLength")

    def allValid[A](validators: Validator[A]*): Validator[A] =
      a => validators.map(f => f(a)).foldLeft(Validated.validNel[String, A](a))((acc, v) => acc *> v)
  }

  def decodeAll(root: HCursor): PatchDecoder ~>: Compose2[AccumulatingResult, Option, *] =
    Lambda[PatchDecoder ~>: Compose2[Decoder.AccumulatingResult, Option, *]](_.decode(root))

  val validateAll: Tuple2K[PatchResult, Validator, *] ~>: ValidateResult =
    new (Tuple2K[PatchResult, Validator, *] ~>: ValidateResult) {
      override def apply[Z](fa: (PatchResult[Z], Validator[Z])): ValidateResult[Z] = {
        import cats.instances.option._
        val progress  = fa._1
        val validator = fa._2

        progress.leftMap(_.map(_.show)).andThen(_.traverse(validator))
      }
    }

  def decodeAndValidate[F[_[_]]](
      decoders: F[PatchDecoder],
      validators: F[Validator],
      root: HCursor
  )(implicit FA: ApplicativeKC[F], FT: TraverseKC[F]): ValidatedNel[String, F[Option]] =
    FT.sequenceK(
      FA.map2K(FA.mapK(decoders)(decodeAll(root)), validators)(validateAll)
    )

  def toListK[F[_[_]], A](values: F[Const[A, *]])(implicit F: FoldableKC[F]): List[A] = {
    import cats.instances.list._
    F.foldMapK(values)(FunctionK.liftConst(a => List(a)))
  }

  def countDefined[F[_[_]]](values: F[Option])(implicit F: FoldableKC[F]): Int = {
    import cats.instances.int._
    F.foldMapK(values)(λ[Option ~>: Const[Int, *]](fa => if (fa.isDefined) 1 else 0))
  }

}
