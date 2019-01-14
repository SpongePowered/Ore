package controllers.sugar

import scala.language.higherKinds

import java.util.Date

import scala.concurrent.{ExecutionContext, Future}

import play.api.i18n.Messages
import play.api.mvc.Results.{Redirect, Unauthorized}
import play.api.mvc._

import controllers.routes
import controllers.sugar.Requests._
import db.ModelService
import db.access.ModelAccess
import db.impl.OrePostgresDriver.api._
import db.impl.access.{OrganizationBase, ProjectBase, UserBase}
import models.project.{Project, Visibility}
import models.user.{Organization, SignOn, User}
import models.viewhelper._
import ore.permission.scope.{GlobalScope, HasScope}
import ore.permission.{EditPages, EditSettings, HideProjects, Permission}
import security.spauth.{SingleSignOnConsumer, SpongeAuthApi}
import util.{IOUtils, OreMDC}

import cats.data.OptionT
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import com.typesafe.scalalogging

/**
  * A set of actions used by Ore.
  */
trait Actions extends Calls with ActionHelpers {

  implicit def service: ModelService
  def sso: SingleSignOnConsumer
  def signOns: ModelAccess[SignOn]
  def bakery: Bakery
  implicit def auth: SpongeAuthApi

  def users: UserBase                 = UserBase()
  def projects: ProjectBase           = ProjectBase()
  def organizations: OrganizationBase = OrganizationBase()

  private val PermsLogger    = scalalogging.Logger("Permissions")
  private val MDCPermsLogger = scalalogging.Logger.takingImplicit[OreMDC](PermsLogger.underlying)

  val AuthTokenName = "_oretoken"

  /** Called when a [[User]] tries to make a request they do not have permission for */
  def onUnauthorized(implicit request: Request[_]): Future[Result] = {
    val noRedirect = request.flash.get("noRedirect")
    import OreMDC.Implicits.noCtx
    users.current.isEmpty
      .map { currentUserEmpty =>
        if (noRedirect.isEmpty && currentUserEmpty)
          Redirect(routes.Users.logIn(None, None, Some(request.path)))
        else
          Redirect(ShowHome)
      }
      .unsafeToFuture()
  }

  /**
    * Action to perform a permission check for the current ScopedRequest and
    * given Permission.
    *
    * @param p Permission to check
    * @tparam R Type of ScopedRequest that is being checked
    * @return The ScopedRequest as an instance of R
    */
  def PermissionAction[R[_] <: ScopedRequest[_]](
      p: Permission
  )(implicit ec: ExecutionContext, cs: ContextShift[IO], hasScope: HasScope[R[_]]): ActionRefiner[R, R] =
    new ActionRefiner[R, R] {
      def executionContext: ExecutionContext = ec

      private def log(success: Boolean, request: R[_]): Unit = {
        val lang = if (success) "GRANTED" else "DENIED"
        MDCPermsLogger.debug(s"<PERMISSION $lang> ${request.user.name}@${request.path.substring(1)}")(
          OreMDC.RequestMDC(request)
        )
      }

      def refine[A](request: R[A]): Future[Either[Result, R[A]]] = {
        implicit val r: R[A] = request

        request.user.can(p).in(request).unsafeToFuture().flatMap { perm =>
          log(success = perm, request)
          if (!perm) onUnauthorized.map(Left.apply)
          else Future.successful(Right(request))
        }
      }
    }

  /**
    * A PermissionAction that uses an AuthedProjectRequest for the
    * ScopedRequest.
    *
    * @param p Permission to check
    * @return An [[ProjectRequest]]
    */
  def ProjectPermissionAction(p: Permission)(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[AuthedProjectRequest, AuthedProjectRequest] = PermissionAction[AuthedProjectRequest](p)

  /**
    * A PermissionAction that uses an AuthedOrganizationRequest for the
    * ScopedRequest.
    *
    * @param p Permission to check
    * @return [[OrganizationRequest]]
    */
  def OrganizationPermissionAction(p: Permission)(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[AuthedOrganizationRequest, AuthedOrganizationRequest] =
    PermissionAction[AuthedOrganizationRequest](p)

  implicit final class ResultWrapper(result: Result) {

    /**
      * Adds a new session cookie to the result for the specified [[User]].
      *
      * @param user   User to create session for
      * @param maxAge Maximum session age
      * @return Result with token
      */
    def authenticatedAs(user: User, maxAge: Int = -1): IO[Result] = {
      val session = users.createSession(user)
      val age     = if (maxAge == -1) None else Some(maxAge)
      session.map { s =>
        result.withCookies(bakery.bake(AuthTokenName, s.token, age))
      }
    }

    /**
      * Indicates that the client's session cookie should be cleared.
      *
      * @return
      */
    def clearingSession(): Result = result.discardingCookies(DiscardingCookie(AuthTokenName))

  }

  /**
    * Returns true and marks the nonce as used if the specified nonce has not
    * been used, has not expired.
    *
    * @param nonce Nonce to check
    * @return True if valid
    */
  def isNonceValid(nonce: String): IO[Boolean] =
    this.signOns
      .findNow(_.nonce === nonce)
      .semiflatMap { signOn =>
        if (signOn.isCompleted || new Date().getTime - signOn.createdAt.getTime > 600000)
          IO.pure(false)
        else {
          service.update(signOn.copy(isCompleted = true)).as(true)
        }
      }
      .exists(identity)

  /**
    * Returns a NotFound result with the 404 HTML template.
    *
    * @return NotFound
    */
  def notFound(implicit request: OreRequest[_]): Result

  // Implementation

  def userLock(redirect: Call)(implicit ec: ExecutionContext): ActionFilter[AuthRequest] =
    new ActionFilter[AuthRequest] {
      def executionContext: ExecutionContext = ec

      def filter[A](request: AuthRequest[A]): Future[Option[Result]] = Future.successful {
        if (!request.user.isLocked) None
        else Some(Redirect(redirect).withError("error.user.locked"))
      }
    }

  def verifiedAction(sso: Option[String], sig: Option[String])(
      implicit ec: ExecutionContext
  ): ActionFilter[AuthRequest] = new ActionFilter[AuthRequest] {
    def executionContext: ExecutionContext = ec

    def filter[A](request: AuthRequest[A]): Future[Option[Result]] = {
      val auth = for {
        ssoSome <- OptionT.fromOption[IO](sso)
        sigSome <- OptionT.fromOption[IO](sig)
        res     <- Actions.this.sso.authenticate(ssoSome, sigSome)(isNonceValid)(OreMDC.RequestMDC(request))
      } yield res

      auth
        .cata(
          Some(Unauthorized),
          spongeUser => if (spongeUser.id == request.user.id.value) None else Some(Unauthorized)
        )
        .unsafeToFuture()
    }
  }

  def userAction(username: String)(implicit ec: ExecutionContext, cs: ContextShift[IO]): ActionFilter[AuthRequest] =
    new ActionFilter[AuthRequest] {
      def executionContext: ExecutionContext = ec

      def filter[A](request: AuthRequest[A]): Future[Option[Result]] =
        users
          .requestPermission(request.user, username, EditSettings)(auth, cs, OreMDC.RequestMDC(request))
          .transform {
            case None    => Some(Unauthorized) // No Permission
            case Some(_) => None // Permission granted => No Filter
          }
          .value
          .unsafeToFuture()
    }

  def oreAction(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionTransformer[Request, OreRequest] = new ActionTransformer[Request, OreRequest] {
    def executionContext: ExecutionContext = ec

    def transform[A](request: Request[A]): Future[OreRequest[A]] = {
      HeaderData
        .of(request)
        .map { data =>
          val requestWithLang =
            data.currentUser.flatMap(_.lang).fold(request)(lang => request.addAttr(Messages.Attrs.CurrentLang, lang))
          new SimpleOreRequest(data, requestWithLang)
        }
        .unsafeToFuture()
    }
  }

  def authAction(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[Request, AuthRequest] = new ActionRefiner[Request, AuthRequest] {
    def executionContext: ExecutionContext = ec

    def refine[A](request: Request[A]): Future[Either[Result, AuthRequest[A]]] =
      maybeAuthRequest(request, users.current(request, auth, OreMDC.NoMDC))

  }

  private def maybeAuthRequest[A](
      request: Request[A],
      userF: OptionT[IO, User]
  )(implicit cs: ContextShift[IO]): Future[Either[Result, AuthRequest[A]]] =
    userF
      .semiflatMap(user => HeaderData.of(request).map(new AuthRequest(user, _, request)))
      .toRight(IO.fromFuture(IO(onUnauthorized(request))))
      .leftSemiflatMap(identity)
      .value
      .unsafeToFuture()

  def projectAction(author: String, slug: String)(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[OreRequest, ProjectRequest] = new ActionRefiner[OreRequest, ProjectRequest] {
    def executionContext: ExecutionContext = ec

    def refine[A](request: OreRequest[A]): Future[Either[Result, ProjectRequest[A]]] =
      maybeProjectRequest(request, projects.withSlug(author, slug))
  }

  def projectAction(pluginId: String)(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[OreRequest, ProjectRequest] = new ActionRefiner[OreRequest, ProjectRequest] {
    def executionContext: ExecutionContext = ec

    def refine[A](request: OreRequest[A]): Future[Either[Result, ProjectRequest[A]]] =
      maybeProjectRequest(request, projects.withPluginId(pluginId))
  }

  private def maybeProjectRequest[A](r: OreRequest[A], project: OptionT[IO, Project])(
      implicit cs: ContextShift[IO]
  ): Future[Either[Result, ProjectRequest[A]]] = {
    implicit val request: OreRequest[A] = r
    project
      .flatMap(processProject(_, request.headerData.currentUser))
      .semiflatMap { p =>
        toProjectRequest(p) {
          case (data, scoped) => new ProjectRequest[A](data, scoped, r.headerData, r)
        }
      }
      .toRight(notFound)
      .value
      .unsafeToFuture()
  }

  private def toProjectRequest[T](project: Project)(f: (ProjectData, ScopedProjectData) => T)(
      implicit
      request: OreRequest[_],
      cs: ContextShift[IO]
  ) =
    (ProjectData.of(project), ScopedProjectData.of(request.headerData.currentUser, project)).parMapN(f)

  private def processProject(project: Project, user: Option[User])(
      implicit cs: ContextShift[IO]
  ): OptionT[IO, Project] = {
    if (project.visibility == Visibility.Public || project.visibility == Visibility.New) {
      OptionT.pure[IO](project)
    } else {
      OptionT
        .fromOption[IO](user)
        .semiflatMap { user =>
          val check1 = canEditAndNeedChangeOrApproval(project, user)
          val check2 = user.can(HideProjects).in(GlobalScope)

          IOUtils.raceBoolean(check1, check2)
        }
        .subflatMap {
          case true  => Some(project)
          case false => None
        }
    }
  }

  private def canEditAndNeedChangeOrApproval(project: Project, user: User)(implicit cs: ContextShift[IO]) = {
    if (project.visibility == Visibility.NeedsChanges || project.visibility == Visibility.NeedsApproval) {
      user.can(EditPages).in(project)
    } else {
      IO.pure(false)
    }
  }

  def authedProjectActionImpl(project: OptionT[IO, Project])(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[AuthRequest, AuthedProjectRequest] = new ActionRefiner[AuthRequest, AuthedProjectRequest] {

    def executionContext: ExecutionContext = ec

    def refine[A](request: AuthRequest[A]): Future[Either[Result, AuthedProjectRequest[A]]] = {
      implicit val r: AuthRequest[A] = request

      project
        .flatMap(processProject(_, Some(request.user)))
        .semiflatMap { p =>
          toProjectRequest(p) {
            case (data, scoped) => new AuthedProjectRequest[A](data, scoped, r.headerData, request)
          }
        }
        .toRight(notFound)
        .value
        .unsafeToFuture()
    }
  }

  def authedProjectAction(author: String, slug: String)(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[AuthRequest, AuthedProjectRequest] = authedProjectActionImpl(projects.withSlug(author, slug))

  def authedProjectActionById(pluginId: String)(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[AuthRequest, AuthedProjectRequest] = authedProjectActionImpl(projects.withPluginId(pluginId))

  def organizationAction(organization: String)(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[OreRequest, OrganizationRequest] = new ActionRefiner[OreRequest, OrganizationRequest] {

    def executionContext: ExecutionContext = ec

    def refine[A](request: OreRequest[A]): Future[Either[Result, OrganizationRequest[A]]] = {
      implicit val r: OreRequest[A] = request
      getOrga(organization)
        .semiflatMap { org =>
          toOrgaRequest(org) {
            case (data, scoped) => new OrganizationRequest[A](data, scoped, r.headerData, request)
          }
        }
        .toRight(notFound)
        .value
        .unsafeToFuture()
    }
  }

  def authedOrganizationAction(organization: String)(
      implicit ec: ExecutionContext,
      cs: ContextShift[IO]
  ): ActionRefiner[AuthRequest, AuthedOrganizationRequest] = new ActionRefiner[AuthRequest, AuthedOrganizationRequest] {
    def executionContext: ExecutionContext = ec

    def refine[A](request: AuthRequest[A]): Future[Either[Result, AuthedOrganizationRequest[A]]] = {
      implicit val r: AuthRequest[A] = request

      getOrga(organization)
        .semiflatMap { org =>
          toOrgaRequest(org) {
            case (data, scoped) => new AuthedOrganizationRequest[A](data, scoped, r.headerData, request)
          }
        }
        .toRight(notFound)
        .value
        .unsafeToFuture()
    }

  }

  private def toOrgaRequest[T](orga: Organization)(f: (OrganizationData, ScopedOrganizationData) => T)(
      implicit request: OreRequest[_],
      cs: ContextShift[IO]
  ) = (OrganizationData.of(orga), ScopedOrganizationData.of(request.headerData.currentUser, orga)).parMapN(f)

  def getOrga(organization: String): OptionT[IO, Organization] =
    organizations.withName(organization)

  def getUserData(request: OreRequest[_], userName: String)(implicit cs: ContextShift[IO]): OptionT[IO, UserData] =
    users.withName(userName)(auth, OreMDC.RequestMDC(request)).semiflatMap(UserData.of(request, _))

}
