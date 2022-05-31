package controllers.sugar

import scala.language.implicitConversions

import scala.concurrent.Future

import play.api.data.{Form, FormBinding, FormError}
import play.api.mvc.Results.Redirect
import play.api.mvc._

import controllers.sugar.Requests.OreRequest

import cats.Monad
import cats.data.{EitherT, OptionT}
import com.google.common.base.Preconditions.checkArgument
import zio.blocking.Blocking
import zio.clock.Clock
import zio.{IO, ZIO}

/**
  * A helper class for some common functions of controllers.
  */
trait ActionHelpers {

  /**
    * Returns a NotFound result with the 404 HTML template.
    *
    * @return NotFound
    */
  def notFound(implicit request: OreRequest[_]): Result

  /**
    * Redirects to the specified call with the errors of the specified
    * Form.
    *
    * @param call Call to redirect to
    * @param form Form with error
    * @return     Redirect to call
    */
  def FormErrorLocalized(call: Call): Form[_] => Result = form => {
    checkArgument(form.errors.nonEmpty, "no errors", "")
    val errors = form.errors.map(e => s"${e.message}.${e.key}")
    Redirect(call).withErrors(errors.toList)
  }

  implicit def toOreResultOps(result: Result): ActionHelpers.OreResultOps =
    new ActionHelpers.OreResultOps(result)

  implicit def toFormBindOps[A](form: Form[A]): ActionHelpers.FormBindOps[A] =
    new ActionHelpers.FormBindOps(form)

  implicit def toOreRequestBuilderOps[R[_], B](
      builder: ActionBuilder[R, B]
  ): ActionHelpers.OreActionBuilderOps[R, B] =
    new ActionHelpers.OreActionBuilderOps[R, B](builder)
}
object ActionHelpers {

  class OreResultOps(private val result: Result) {

    /**
      * Adds an alert message to the result.
      *
      * @param tpe    Alert type
      * @param alert  Alert message
      * @return       Result with error
      */
    def withAlert(tpe: String, alert: String): Result = {
      val flash = result.newFlash.fold(Flash(Map(tpe -> alert)))(f => Flash(f.data + (tpe -> alert)))
      result.flashing(flash)
    }

    /**
      * Adds one or more alerts messages to the result.
      *
      * @param tpe    Alert type
      * @param alerts  Alert messages
      * @return        Result with alerts
      */
    def withAlerts(tpe: String, alerts: List[String]): Result = alerts match {
      case Nil           => result
      case single :: Nil => withAlert(tpe, single)
      case multiple =>
        val numPart   = s"$tpe-num" -> multiple.size.toString
        val newValues = numPart :: multiple.zipWithIndex.map { case (e, i) => s"$tpe-$i" -> e }

        val flash = result.newFlash.fold(Flash(newValues.toMap))(f => Flash(f.data ++ newValues))

        result.flashing(flash)
    }

    /**
      * Adds an error message to the result.
      *
      * @param error  Error message
      * @return       Result with error
      */
    def withError(error: String): Result = withAlert("error", error)

    /**
      * Adds one or more error messages to the result.
      *
      * @param errors  Error messages
      * @return        Result with errors
      */
    def withErrors(errors: List[String]): Result = withAlerts("error", errors)
  }

  class FormBindOps[A](private val form: Form[A]) extends AnyVal {
    def bindZIO[B](error: Form[A] => B)(implicit request: Request[_], formBinding: FormBinding): IO[B, A] =
      form.bindFromRequest().fold(f => ZIO.fail(error(f)), ZIO.succeed[A](_))

    def bindEitherT[F[_]] = new BindFormEitherTPartiallyApplied[F, A](form)

    def bindOptionT[F[_]](implicit F: Monad[F], request: Request[_], formBinding: FormBinding): OptionT[F, A] =
      form.bindFromRequest().fold(_ => OptionT.none[F, A], OptionT.some[F](_))
  }

  final class BindFormEitherTPartiallyApplied[F[_], B](private val form: Form[B]) extends AnyVal {
    def apply[A](
        left: Form[B] => A
    )(implicit F: Monad[F], request: Request[_], formBinding: FormBinding): EitherT[F, A, B] =
      form.bindFromRequest().fold(left.andThen(EitherT.leftT[F, B](_)), EitherT.rightT[F, A](_))
  }

  private[sugar] def zioToFuture[A](
      io: ZIO[Blocking with Clock, Nothing, A]
  )(implicit runtime: zio.Runtime[Blocking with Clock]): Future[A] =
    //TODO: If Sentry can't differentiate different errors here, log the error, and throw an exception ignored by Sentry instead
    runtime.unsafeRunToFuture(io)

  class OreActionBuilderOps[R[_], B](private val action: ActionBuilder[R, B]) extends AnyVal {

    def asyncF(
        fr: ZIO[Blocking, Result, Result]
    )(implicit runtime: zio.Runtime[Blocking with Clock]): Action[AnyContent] =
      action.async(zioToFuture(fr.either.map(_.merge)))

    def asyncF(
        fr: R[B] => ZIO[Blocking, Result, Result]
    )(implicit runtime: zio.Runtime[Blocking with Clock]): Action[B] =
      action.async(r => zioToFuture(fr(r).either.map(_.merge)))

    def asyncF[A](
        bodyParser: BodyParser[A]
    )(fr: R[A] => ZIO[Blocking, Result, Result])(implicit runtime: zio.Runtime[Blocking with Clock]): Action[A] =
      action.async(bodyParser)(r => zioToFuture(fr(r).either.map(_.merge)))
  }
}
