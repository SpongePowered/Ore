package controllers.apiv2

import java.time.OffsetDateTime

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import play.api.inject.ApplicationLifecycle
import play.api.mvc._

import controllers.apiv2.helpers.{APIScope, ApiError, ApiErrors}
import controllers.sugar.CircePlayController
import controllers.sugar.Requests.{ApiAuthInfo, ApiRequest}
import controllers.{OreBaseController, OreControllerComponents}
import db.impl.query.apiv2.{APIV2Queries, AuthQueries}
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{OrganizationTable, ProjectTable, UserTable}
import ore.models.api.ApiSession
import ore.permission.Permission
import ore.permission.scope.{GlobalScope, OrganizationScope, ProjectScope, Scope}

import akka.http.scaladsl.model.ErrorInfo
import akka.http.scaladsl.model.headers.{Authorization, HttpCredentials}
import cats.data.NonEmptyList
import cats.syntax.all._
import zio.interop.catz._
import zio.{IO, Task, UIO, ZIO}

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

  def apiAction(scope: APIScope): ActionRefiner[Request, ApiRequest] = new ActionRefiner[Request, ApiRequest] {
    def executionContext: ExecutionContext = ec

    override protected def refine[A](request: Request[A]): Future[Either[Result, ApiRequest[A]]] = {
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
        scopePerms <- {
          val res: IO[Result, Permission] =
            apiScopeToRealScope(scope).flatMap(info.permissionIn(_)).orElseFail(NotFound)
          res
        }
        res <- {
          if (info.expires.isBefore(OffsetDateTime.now())) {
            service.deleteWhere(ApiSession)(_.token === token) *> IO.fail(unAuth("Api session expired"))
          } else ZIO.succeed(ApiRequest(info, scopePerms, request))
        }
      } yield res

      zioToFuture(authRequest.either)
    }
  }

  def apiScopeToRealScope(scope: APIScope): IO[Unit, Scope] = scope match {
    case APIScope.GlobalScope => UIO.succeed(GlobalScope)
    case APIScope.ProjectScope(projectOwner, projectSlug) =>
      service
        .runDBIO(
          TableQuery[ProjectTable]
            .filter(p => p.ownerName === projectOwner && p.slug.toLowerCase === projectSlug.toLowerCase)
            .map(_.id)
            .result
            .headOption
        )
        .get
        .orElseFail(())
        .map(ProjectScope)
    case APIScope.OrganizationScope(organizationName) =>
      val q = for {
        u <- TableQuery[UserTable]
        if u.name === organizationName
        o <- TableQuery[OrganizationTable] if u.id === o.id
      } yield o.id

      service
        .runDBIO(q.result.headOption)
        .get
        .orElseFail(())
        .map(OrganizationScope)
  }

  def createApiScope(
      projectOwner: Option[String],
      projectSlug: Option[String],
      organizationName: Option[String]
  ): Either[Result, APIScope] = {
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

  def permissionsInApiScope(
      projectOwner: Option[String],
      projectSlug: Option[String],
      organizationName: Option[String]
  )(
      implicit request: ApiRequest[_]
  ): IO[Result, (APIScope, Permission)] =
    for {
      apiScope <- ZIO.fromEither(createApiScope(projectOwner, projectSlug, organizationName))
      scope    <- apiScopeToRealScope(apiScope).orElseFail(NotFound)
      perms    <- request.permissionIn(scope)
    } yield (apiScope, perms)

  def permApiAction(perms: Permission): ActionFilter[ApiRequest] = new ActionFilter[ApiRequest] {
    override protected def executionContext: ExecutionContext = ec

    override protected def filter[A](request: ApiRequest[A]): Future[Option[Result]] =
      if (request.scopePermission.has(perms)) Future.successful(None)
      else Future.successful(Some(Forbidden))
  }

  def cachingAction: ActionFunction[ApiRequest, ApiRequest] =
    new ActionFunction[ApiRequest, ApiRequest] {
      override protected def executionContext: ExecutionContext = ec

      override def invokeBlock[A](request: ApiRequest[A], block: ApiRequest[A] => Future[Result]): Future[Result] = {
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

  def ApiAction(perms: Permission, scope: APIScope): ActionBuilder[ApiRequest, AnyContent] =
    Action.andThen(apiAction(scope)).andThen(permApiAction(perms))

  def CachingApiAction(perms: Permission, scope: APIScope): ActionBuilder[ApiRequest, AnyContent] =
    ApiAction(perms, scope).andThen(cachingAction)
}
