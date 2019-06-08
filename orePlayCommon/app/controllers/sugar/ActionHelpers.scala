package controllers.sugar

import scala.language.{higherKinds, implicitConversions}

import scala.concurrent.Future

import play.api.data.{Form, FormError}
import play.api.mvc.Results.Redirect
import play.api.mvc._

import controllers.sugar.Requests.OreRequest

import cats.Monad
import cats.data.{EitherT, OptionT}
import com.google.common.base.Preconditions.checkArgument
import scalaz.zio
import scalaz.zio.interop.catz._
import scalaz.zio.blocking.Blocking
import scalaz.zio.{IO, UIO, ZIO}

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

  /**
    * Redirects to the specified call with the errors of the specified
    * Form.
    *
    * @param call Call to redirect to
    * @param form Form with error
    * @return     Redirect to call
    */
  def FormError(call: Call): Form[_] => Result = form => {
    checkArgument(form.errors.nonEmpty, "no errors", "")
    Redirect(call).withFormErrors(form.errors)
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

    /**
      * Adds one or more form error messages to the result.
      *
      * @param errors  Error messages
      * @return        Result with errors
      */
    def withFormErrors(errors: Seq[FormError]): Result = withAlerts("error", errors.flatMap(_.messages).toList)

    /**
      * Adds a success message to the result.
      *
      * @param message  Success message
      * @return         Result with message
      */
    def withSuccess(message: String): Result = withAlert("success", message)

    /**
      * Adds one or more success messages to the result.
      *
      * @param messages  Success messages
      * @return          Result with message
      */
    def withSuccesses(messages: List[String]): Result = withAlerts("success", messages)

    /**
      * Adds a warning info to the result.
      *
      * @param message  Info message
      * @return         Result with message
      */
    def withInfo(message: String): Result = withAlert("info", message)

    /**
      * Adds one or more info messages to the result.
      *
      * @param messages  Info messages
      * @return          Result with message
      */
    def withInfo(messages: List[String]): Result = withAlerts("info", messages)

    /**
      * Adds a warning message to the result.
      *
      * @param message  Warning message
      * @return         Result with message
      */
    def withWarning(message: String): Result = withAlert("warning", message)

    /**
      * Adds one or more warning messages to the result.
      *
      * @param messages  Warning messages
      * @return          Result with message
      */
    def withWarnings(messages: List[String]): Result = withAlerts("warning", messages)
  }

  class FormBindOps[A](private val form: Form[A]) extends AnyVal {
    def bindEitherT[F[_]] = new BindFormEitherTPartiallyApplied[F, A](form)

    def bindOptionT[F[_]](implicit F: Monad[F], request: Request[_]): OptionT[F, A] =
      form.bindFromRequest().fold(_ => OptionT.none[F, A], OptionT.some[F](_))
  }

  final class BindFormEitherTPartiallyApplied[F[_], B](private val form: Form[B]) extends AnyVal {
    def apply[A](left: Form[B] => A)(implicit F: Monad[F], request: Request[_]): EitherT[F, A, B] =
      form.bindFromRequest().fold(left.andThen(EitherT.leftT[F, B](_)), EitherT.rightT[F, A](_))
  }

  private[sugar] def zioToFuture[A](io: ZIO[Blocking, Nothing, A])(implicit runtime: zio.Runtime[Blocking]): Future[A] =
    //TODO: If Sentry can't differentiate different errors here, log the error, and throw an exception ignored by Sentry instead
    runtime.unsafeRun(io.toFutureWith(_ => new Exception(s"Got impossible nothing")))

  class OreActionBuilderOps[R[_], B](private val action: ActionBuilder[R, B]) extends AnyVal {

    def asyncF(fr: ZIO[Blocking, Nothing, Result])(implicit runtime: zio.Runtime[Blocking]): Action[AnyContent] =
      action.async(zioToFuture(fr))

    def asyncF(fr: R[B] => ZIO[Blocking, Nothing, Result])(implicit runtime: zio.Runtime[Blocking]): Action[B] =
      action.async(r => zioToFuture(fr(r)))

    def asyncF[A](
        bodyParser: BodyParser[A]
    )(fr: R[A] => ZIO[Blocking, Nothing, Result])(implicit runtime: zio.Runtime[Blocking]): Action[A] =
      action.async(bodyParser)(r => zioToFuture(fr(r)))

    def asyncBIO(fr: ZIO[Blocking, Result, Result])(implicit runtime: zio.Runtime[Blocking]): Action[AnyContent] =
      action.async(zioToFuture(fr.either.map(_.merge)))

    def asyncBIO(fr: R[B] => ZIO[Blocking, Result, Result])(implicit runtime: zio.Runtime[Blocking]): Action[B] =
      action.async(r => zioToFuture(fr(r).either.map(_.merge)))

    def asyncBIO[A](
        bodyParser: BodyParser[A]
    )(fr: R[A] => ZIO[Blocking, Result, Result])(implicit runtime: zio.Runtime[Blocking]): Action[A] =
      action.async(bodyParser)(r => zioToFuture(fr(r).either.map(_.merge)))
  }
}
