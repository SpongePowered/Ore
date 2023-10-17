package controllers.apiv2

import java.time.OffsetDateTime

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import play.api.inject.ApplicationLifecycle
import play.api.mvc._

import controllers.apiv2.helpers.{APIScope, ApiError, ApiErrors}
import controllers.sugar.CircePlayController
import controllers.sugar.Requests.ApiRequest
import controllers.{OreBaseController, OreControllerComponents}
import db.impl.query.apiv2.AuthQueries
import ore.db.impl.OrePostgresDriver.api._
import ore.models.api.ApiSession
import ore.permission.Permission
import ore.permission.scope.Scope

import akka.http.scaladsl.model.ErrorInfo
import akka.http.scaladsl.model.headers.{Authorization, HttpCredentials}
import cats.data.NonEmptyList
import cats.syntax.all._
import zio.interop.catz._
import zio.{IO, Task, ZIO}

abstract class AbstractApiV2Controller(lifecycle: ApplicationLifecycle)(
    implicit oreComponents: OreControllerComponents
) extends OreBaseController
    with CircePlayController {

  implicit def zioMode[R]: scalacache.Mode[ZIO[R, Throwable, *]] =
    scalacache.CatsEffect.modes.async[ZIO[R, Throwable, *]]

  private val actionResultCache = scalacache.caffeine.CaffeineCache[Future[Result]]
  lifecycle.addStopHook(() => zioRuntime.unsafeRunToFuture(actionResultCache.close[Task]()))

  protected def limitOrDefault(limit: Option[Long], default: Long): Long = math.min(limit.getOrElse(default), default)
  protected def offsetOrZero(offset: Long): Long                         = math.max(offset, 0)

  sealed trait ParseAuthHeaderError {
    private def unAuth(firstError: String, otherErrors: String*) = {
      val res =
        if (otherErrors.isEmpty) Unauthorized(ApiError(firstError))
        else Unauthorized(ApiErrors(NonEmptyList.of(firstError, otherErrors: _*)))

      res.withHeaders(WWW_AUTHENTICATE -> "OreApi")
    }

    import ParseAuthHeaderError._
    def toResult: Result = this match {
      case NoAuthHeader               => unAuth("No authorization specified")
      case UnparsableHeader           => unAuth("Could not parse authorization header")
      case ErrorParsingHeader(errors) => unAuth(errors.head.summary, errors.tail.map(_.summary): _*)
      case InvalidScheme              => unAuth("Invalid scheme for authorization. Needs to be OreApi")
    }
  }
  object ParseAuthHeaderError {
    case object NoAuthHeader                                       extends ParseAuthHeaderError
    case object UnparsableHeader                                   extends ParseAuthHeaderError
    case class ErrorParsingHeader(errors: NonEmptyList[ErrorInfo]) extends ParseAuthHeaderError
    case object InvalidScheme                                      extends ParseAuthHeaderError
  }

  protected def parseAuthHeader(request: Request[_]): IO[ParseAuthHeaderError, HttpCredentials] = {
    import ParseAuthHeaderError._

    for {
      stringAuth <- ZIO.fromOption(request.headers.get(AUTHORIZATION)).orElseFail(NoAuthHeader)
      parsedAuth = Authorization
        .parseFromValueString(stringAuth)
        .leftMap(NonEmptyList.fromList(_).fold[ParseAuthHeaderError](UnparsableHeader)(ErrorParsingHeader))
      auth <- ZIO.fromEither(parsedAuth)
      creds = auth.credentials
      res <- {
        if (creds.scheme == "OreApi")
          ZIO.succeed(creds)
        else
          ZIO.fail(InvalidScheme)
      }
    } yield res
  }

  def apiAction[S <: Scope](scope: APIScope[S]): ActionRefiner[Request, ApiRequest[S, *]] =
    new ActionRefiner[Request, ApiRequest[S, *]] {
      def executionContext: ExecutionContext = ec

      override protected def refine[A](request: Request[A]): Future[Either[Result, ApiRequest[S, A]]] = {
        def unAuth(msg: String) = Unauthorized(ApiError(msg)).withHeaders(WWW_AUTHENTICATE -> "OreApi")

        val authRequest = for {
          creds <- parseAuthHeader(request).mapError(_.toResult)
          token <- ZIO
            .fromOption(creds.params.get("session"))
            .orElseFail(unAuth("No session specified"))
          info <- service
            .runDbCon(AuthQueries.getApiAuthInfo(token).option)
            .get
            .orElseFail(unAuth("Invalid session"))
          realScope  <- scope.toRealScope.orElseFail(NotFound)
          scopePerms <- info.permissionIn(realScope)
          res <- {
            if (info.expires.isBefore(OffsetDateTime.now())) {
              service.deleteWhere(ApiSession)(_.token === token) *> IO.fail(unAuth("Api session expired"))
            } else ZIO.succeed(ApiRequest(info, scopePerms, realScope, request))
          }
        } yield res

        zioToFuture(authRequest.either)
      }
    }

  def createApiScope(
      projectOwner: Option[String],
      projectSlug: Option[String],
      organizationName: Option[String]
  ): Either[Result, APIScope[_ <: Scope]] = {
    val projectOwnerName = projectOwner.zip(projectSlug)

    if ((projectOwner.isDefined || projectSlug.isDefined) && projectOwnerName.isEmpty) {
      Left(BadRequest(ApiError("You need to specify both the project owner and slug at the same time, not just one")))
    } else {
      (projectOwnerName, organizationName) match {
        case (Some(_), Some(_)) =>
          Left(BadRequest(ApiError("Can't check for project and organization permissions at the same time")))
        case (Some((owner, name)), None) => Right(APIScope.ProjectScope(owner, name))
        case (None, Some(orgName))       => Right(APIScope.OrganizationScope(orgName))
        case (None, None)                => Right(APIScope.GlobalScope)
      }
    }
  }

  def permApiAction[S <: Scope](perms: Permission): ActionFilter[ApiRequest[S, *]] =
    new ActionFilter[ApiRequest[S, *]] {
      override protected def executionContext: ExecutionContext = ec

      override protected def filter[A](request: ApiRequest[S, A]): Future[Option[Result]] =
        if (request.scopePermission.has(perms)) Future.successful(None)
        else Future.successful(Some(Forbidden))
    }

  def cachingAction[S <: Scope]: ActionFunction[ApiRequest[S, *], ApiRequest[S, *]] =
    new ActionFunction[ApiRequest[S, *], ApiRequest[S, *]] {
      override protected def executionContext: ExecutionContext = ec

      override def invokeBlock[A](
          request: ApiRequest[S, A],
          block: ApiRequest[S, A] => Future[Result]
      ): Future[Result] = {
        import scalacache.modes.scalaFuture._
        require(request.method == "GET")

        if (request.user.isDefined) {
          block(request)
        } else {
          actionResultCache
            .caching[Future](
              request.path,
              request.queryString.toSeq.sortBy(_._1)
            )(Some(5.minute))(block(request))
            .flatten
        }
      }
    }

  def ApiAction[S <: Scope](perms: Permission, scope: APIScope[S]): ActionBuilder[ApiRequest[S, *], AnyContent] =
    Action.andThen(apiAction(scope)).andThen(permApiAction(perms))

  def CachingApiAction[S <: Scope](perms: Permission, scope: APIScope[S]): ActionBuilder[ApiRequest[S, *], AnyContent] =
    ApiAction(perms, scope).andThen(cachingAction)
}
