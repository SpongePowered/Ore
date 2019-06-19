package ore.external

import scala.language.higherKinds

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import ore.external.AkkaClientApi.ClientSettings

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.pattern.CircuitBreaker
import akka.stream.Materializer
import cats.Applicative
import cats.data.EitherT
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.typesafe.scalalogging.Logger
import io.circe.{Decoder, Json}

abstract class AkkaClientApi[F[_], E[_]](serviceName: String, counter: Ref[F, Long], settings: ClientSettings)(
    implicit system: ActorSystem,
    mat: Materializer,
    F: Concurrent[F],
    E: Applicative[E]
) {

  protected def Logger: Logger

  protected val breaker =
    CircuitBreaker(system.scheduler, settings.breakerMaxFailures, settings.breakerTimeoutDur, settings.breakerResetDur)

  breaker.onOpen {
    Logger.error(s"Lost connection to $serviceName. Circuit breaker opened")
  }

  private def nextCounter: F[Long] = counter.modify(c => (c + 1, c))

  protected def apiUri(f: Uri.Path => Uri.Path): Uri = settings.apiUri.withPath(f(settings.apiUri.path))

  private def debugF[A](before: => String, after: A => String, fa: F[A]): F[A] = {
    if (Logger.underlying.isDebugEnabled) {
      nextCounter.flatMap { c =>
        F.delay(Logger.debug(s"$c $before")) *> fa.flatTap(res => F.delay(Logger.debug(s"$c ${after(res)}")))
      }
    } else fa
  }

  private def futureToF[A](future: => Future[A]) = {
    import system.dispatcher
    F.async[A] { callback =>
      future.onComplete(t => callback(t.toEither))
    }
  }

  protected def makeRequest(request: HttpRequest): F[HttpResponse] = {
    debugF(
      s"Making request: $request",
      res => s"Request response: $res",
      futureToF(breaker.withCircuitBreaker(Http().singleRequest(request)))
    )
  }

  private def unmarshallResponse[A](response: HttpResponse)(implicit um: Unmarshaller[HttpResponse, A]): F[A] =
    futureToF(Unmarshal(response).to[A])

  protected def gatherStatusErrors(response: HttpResponse): F[Either[E[String], HttpResponse]] = {
    if (response.status.isSuccess()) F.pure(Right(response))
    else if (response.entity.isKnownEmpty())
      F.delay(response.entity.discardBytes()).as(
        Left(E.pure(s"$serviceName request failed. Response code ${response.status}"))
      )
    else {
      unmarshallResponse[String](response)
        .map(e => Left(E.pure(s"$serviceName request failed. Response code ${response.status}: $e")))
    }
  }

  protected def gatherJsonErrors[A: Decoder](json: Json): Either[E[String], A]

  protected def makeUnmarshallRequestEither[A: Decoder](request: HttpRequest): F[Either[E[String], A]] = {
    import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
    EitherT
      .liftF(makeRequest(request))
      .flatMapF(gatherStatusErrors)
      .semiflatMap(unmarshallResponse[Json])
      .subflatMap(gatherJsonErrors[A])
      .value
  }
}
object AkkaClientApi {

  trait ClientSettings {
    def apiUri: Uri
    def breakerMaxFailures: Int
    def breakerResetDur: FiniteDuration
    def breakerTimeoutDur: FiniteDuration
  }
}
