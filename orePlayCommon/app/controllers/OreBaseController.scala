package controllers

import scala.language.higherKinds

import play.api.i18n.I18nSupport
import play.api.mvc._

import controllers.sugar.Requests.{AuthRequest, AuthedProjectRequest, OreRequest}
import controllers.sugar.{Actions, Requests}
import ore.db.Model
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.VersionTable
import ore.models.project.{Project, Version, Visibility}
import ore.permission.Permission
import util.syntax._

import zio.IO

/**
  * Represents a Secured base Controller for this application.
  */
abstract class OreBaseController(implicit val oreComponents: OreControllerComponents)
    extends AbstractController(oreComponents)
    with Actions
    with I18nSupport {

  implicit val assetsFinder: AssetsFinder = oreComponents.assetsFinder

  override def notFound(implicit request: OreRequest[_]): Result = NotFound(views.html.errors.notFound())

  /**
    * Gets a project with the specified author and slug, or returns a notFound.
    *
    * @param author   Project author
    * @param slug     Project slug
    * @param request  Incoming request
    * @return         NotFound or project
    */
  def getProject(author: String, slug: String)(implicit request: OreRequest[_]): IO[Result, Model[Project]] =
    projects.withSlug(author, slug).get.orElseFail(notFound)

  private def versionFindFunc(versionString: String, canSeeHiden: Boolean): VersionTable => Rep[Boolean] = v => {
    val versionMatches = v.versionString.toLowerCase === versionString.toLowerCase
    val isVisible      = if (canSeeHiden) true.bind else v.visibility === (Visibility.Public: Visibility)
    versionMatches && isVisible
  }

  /**
    * Gets a project with the specified versionString, or returns a notFound.
    *
    * @param project        Project to get version from
    * @param versionString  VersionString
    * @param request        Incoming request
    * @return               NotFound or function result
    */
  def getVersion(project: Model[Project], versionString: String)(
      implicit request: OreRequest[_]
  ): IO[Result, Model[Version]] =
    project
      .versions(ModelView.now(Version))
      .find(versionFindFunc(versionString, request.headerData.globalPerm(Permission.SeeHidden)))
      .toZIOWithError(notFound)

  /**
    * Gets a version with the specified author, project slug and version string
    * or returns a notFound.
    *
    * @param author         Project author
    * @param slug           Project slug
    * @param versionString  VersionString
    * @param request        Incoming request
    * @return               NotFound or project
    */
  def getProjectVersion(author: String, slug: String, versionString: String)(
      implicit request: OreRequest[_]
  ): IO[Result, Model[Version]] =
    for {
      project <- getProject(author, slug)
      version <- getVersion(project, versionString)
    } yield version

  def OreAction: ActionBuilder[OreRequest, AnyContent] = Action.andThen(oreAction)

  /** Ensures a request is authenticated */
  def Authenticated: ActionBuilder[AuthRequest, AnyContent] = Action.andThen(authAction)

  /**
    * Retrieves, processes, and adds a [[Project]] to a request.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Request with a project if found, NotFound otherwise.
    */
  def ProjectAction(author: String, slug: String): ActionBuilder[Requests.ProjectRequest, AnyContent] =
    OreAction.andThen(projectAction(author, slug))

  /**
    * Retrieves, processes, and adds a [[Project]] to a request.
    *
    * @param pluginId The project's unique plugin ID
    * @return         Request with a project if found, NotFound otherwise
    */
  def ProjectAction(pluginId: String): ActionBuilder[Requests.ProjectRequest, AnyContent] =
    OreAction.andThen(projectAction(pluginId))

  /**
    * Ensures a request is authenticated and retrieves, processes, and adds a
    * [[Project]] to a request.
    *
    * @param author Project owner
    * @param slug Project slug
    * @return Authenticated request with a project if found, NotFound otherwise.
    */
  def AuthedProjectAction(
      author: String,
      slug: String
  ): ActionBuilder[AuthedProjectRequest, AnyContent] =
    Authenticated.andThen(authedProjectAction(author, slug))

  /**
    * Ensures a request is authenticated and retrieves and adds a
    * [[Organization]] to the request.
    *
    * @param organization Organization to retrieve
    * @return             Authenticated request with Organization if found, NotFound otherwise
    */
  def AuthedOrganizationAction(
      organization: String
  ): ActionBuilder[Requests.AuthedOrganizationRequest, AnyContent] =
    Authenticated.andThen(authedOrganizationAction(organization))

  /**
    * A request that ensures that a user has permission to edit a specified
    * profile.
    *
    * @param username User to check
    * @return [[OreAction]] if has permission
    */
  def UserEditAction(username: String): ActionBuilder[AuthRequest, AnyContent] =
    Authenticated.andThen(userEditAction(username))
}
