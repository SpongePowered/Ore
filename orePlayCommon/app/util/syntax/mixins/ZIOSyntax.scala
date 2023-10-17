package util.syntax.mixins

import scala.language.implicitConversions

import cats.data.{EitherT, OptionT}
import zio.{IO, UIO}

trait ZIOSyntax {

  implicit def eitherTSyntax[E, A](e: EitherT[UIO, E, A]): ZIOSyntax.EitherTSyntax[E, A] =
    new ZIOSyntax.EitherTSyntax(e)
  implicit def optionTSyntax[A](o: OptionT[UIO, A]): ZIOSyntax.OptionTSyntax[A] = new ZIOSyntax.OptionTSyntax(o)

}
object ZIOSyntax {

  class EitherTSyntax[E, A](private val e: EitherT[UIO, E, A]) extends AnyVal {

    def toZIO: IO[E, A] = e.value.absolve
  }

  class OptionTSyntax[A](private val o: OptionT[UIO, A]) extends AnyVal {

    def toZIO: IO[Unit, A] = o.value.get.orElseFail(())

    def toZIOWithError[E](err: E): IO[E, A] = o.value.get.orElseFail(err)
  }
}
