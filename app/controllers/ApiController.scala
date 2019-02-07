package controllers

import java.sql.Timestamp
import java.util.{Base64, Date, UUID}
import javax.inject.Inject

import scala.concurrent.ExecutionContext

import play.api.cache.AsyncCacheApi
import play.api.i18n.Messages
import play.api.libs.json._
import play.api.mvc._

import controllers.sugar.Bakery
import db.access.ModelAccess
import db.impl.OrePostgresDriver.api._
import db.impl.schema.ProjectApiKeyTable
import db.{DbRef, ModelService}
import form.OreForms
import models.api.ProjectApiKey
import models.project.{Page, Project}
import models.user.{LoggedAction, UserActionLogger}
import ore.permission.role.Role
import ore.permission.{EditApiKeys, ReviewProjects}
import ore.project.factory.ProjectFactory
import ore.project.io.{PluginUpload, ProjectFiles}
import ore.rest.ProjectApiKeyType._
import ore.rest.{OreRestfulApi, OreWrites, ProjectApiKeyType}
import ore.{OreConfig, OreEnv}
import security.CryptoUtils
import security.spauth.{SingleSignOnConsumer, SpongeAuthApi}
import _root_.util.StatusZ
import _root_.util.syntax._

import akka.http.scaladsl.model.Uri
import cats.data.{EitherT, OptionT}
import cats.effect.IO
import cats.instances.list._
import cats.syntax.all._
import com.typesafe.scalalogging

/**
  * Ore API (v1)
  */
final class ApiController @Inject()(
    api: OreRestfulApi,
    status: StatusZ,
    forms: OreForms,
    factory: ProjectFactory
)(
    implicit val ec: ExecutionContext,
    config: OreConfig,
    env: OreEnv,
    service: ModelService,
    bakery: Bakery,
    cache: AsyncCacheApi,
    auth: SpongeAuthApi,
    sso: SingleSignOnConsumer,
) extends OreBaseController
    with OreWrites {

  val files                                      = new ProjectFiles(this.env)
  val projectApiKeys: ModelAccess[ProjectApiKey] = this.service.access[ProjectApiKey]()

  private val Logger = scalalogging.Logger("SSO")

  private def ApiResult(json: Option[JsValue]): Result = json.map(Ok(_)).getOrElse(NotFound)

  /**
    * Returns a JSON view of all projects.
    *
    * @param version    API version
    * @return           JSON view of projects
    */
  def listProjects(
      version: String,
      categories: Option[String],
      sort: Option[Int],
      q: Option[String],
      limit: Option[Long],
      offset: Option[Long]
  ): Action[AnyContent] = Action.asyncF {
    version match {
      case "v1" => this.api.getProjectList(categories, sort, q, limit, offset).map(Ok(_))
      case _    => IO.pure(NotFound)
    }
  }

  /**
    * Returns a JSON view of a Project.
    *
    * @param version    API version
    * @param pluginId   Plugin ID of project
    * @return           Project with Plugin ID
    */
  def showProject(version: String, pluginId: String): Action[AnyContent] = Action.asyncF {
    version match {
      case "v1" => this.api.getProject(pluginId).map(ApiResult)
      case _    => IO.pure(NotFound)
    }
  }

  def createKey(version: String, pluginId: String): Action[AnyContent] =
    Action.andThen(AuthedProjectActionById(pluginId)).andThen(ProjectPermissionAction(EditApiKeys)).asyncF {
      implicit request =>
        version match {
          case "v1" =>
            val projectId = request.data.project.id.value
            val res = for {
              keyType <- forms.ProjectApiKeyCreate.bindOptionT[IO]
              if keyType == Deployment
              exists <- OptionT
                .liftF(this.projectApiKeys.existsNow(k => k.projectId === projectId && k.keyType === keyType))
              if !exists
              pak <- OptionT.liftF(
                this.projectApiKeys.add(
                  ProjectApiKey.partial(
                    projectId = projectId,
                    keyType = keyType,
                    value = UUID.randomUUID().toString.replace("-", "")
                  )
                )
              )
              _ <- OptionT.liftF(
                UserActionLogger.log(
                  request.request,
                  LoggedAction.ProjectSettingsChanged,
                  projectId,
                  s"${request.user.name} created a new ApiKey",
                  ""
                )
              )
            } yield Created(Json.toJson(pak))
            res.getOrElse(BadRequest)
          case _ => IO.pure(NotFound)
        }
    }

  def revokeKey(version: String, pluginId: String): Action[AnyContent] =
    AuthedProjectActionById(pluginId).andThen(ProjectPermissionAction(EditApiKeys)).asyncF { implicit request =>
      version match {
        case "v1" =>
          val res = for {
            optKey <- forms.ProjectApiKeyRevoke.bindOptionT[IO]
            key    <- optKey
            if key.projectId == request.data.project.id.value
            _ <- OptionT.liftF(service.delete(key))
            _ <- OptionT.liftF(
              UserActionLogger.log(
                request.request,
                LoggedAction.ProjectSettingsChanged,
                request.data.project.id,
                s"${request.user.name} removed an ApiKey",
                ""
              )
            )
          } yield Ok
          res.getOrElse(BadRequest)
        case _ => IO.pure(NotFound)
      }
    }

  /**
    * Returns a JSON view of Versions meeting the specified criteria.
    *
    * @param version  API version string
    * @param pluginId Project plugin ID
    * @param channels Channels to get versions from
    * @param limit    Amount to take
    * @param offset   Amount to drop
    * @return         List of versions
    */
  def listVersions(
      version: String,
      pluginId: String,
      channels: Option[String],
      limit: Option[Int],
      offset: Option[Int]
  ): Action[AnyContent] = Action.asyncF {
    version match {
      case "v1" =>
        this.api.getVersionList(pluginId, channels, limit, offset, onlyPublic = true).map(Some.apply).map(ApiResult)
      case _ => IO.pure(NotFound)
    }
  }

  /**
    * Almost like [[listVersions()]] but more intended for internal use. Shows all versions, but need authentification.
    *
    * @param version  API version string
    * @param pluginId Project plugin ID
    * @param channels Channels to get versions from
    * @param limit    Amount to take
    * @param offset   Amount to drop
    * @return         List of versions
    */
  def listAllVersions(
      version: String,
      pluginId: String,
      channels: Option[String],
      limit: Option[Int],
      offset: Option[Int]
  ): Action[AnyContent] =
    AuthedProjectActionById(pluginId).andThen(PermissionAction(ReviewProjects)).asyncF {
      version match {
        case "v1" =>
          this.api.getVersionList(pluginId, channels, limit, offset, onlyPublic = false).map(Some.apply).map(ApiResult)
        case _ => IO.pure(NotFound)
      }
    }

  /**
    * Shows the specified Project Version.
    *
    * @param version  API version
    * @param pluginId Project plugin ID
    * @param name     Version name
    * @return         JSON view of Version
    */
  def showVersion(version: String, pluginId: String, name: String): Action[AnyContent] = Action.asyncF {
    version match {
      case "v1" => this.api.getVersion(pluginId, name).map(ApiResult)
      case _    => IO.pure(NotFound)
    }
  }

  private def error(key: String, error: String)(implicit messages: Messages) =
    Json.obj("errors" -> Map(key -> List(messages(error))))

  def deployVersion(version: String, pluginId: String, name: String): Action[AnyContent] =
    ProjectAction(pluginId).asyncF { implicit request =>
      version match {
        case "v1" =>
          val projectData = request.data
          val project     = projectData.project

          forms.VersionDeploy
            .bindEitherT[IO](
              hasErrors => BadRequest(Json.obj("errors" -> hasErrors.errorsAsJson))
            )
            .flatMap { formData =>
              formData.channel.toRight(BadRequest(Json.obj("errors" -> "Invalid channel"))).map(formData -> _)
            }
            .flatMap {
              case (formData, formChannel) =>
                val apiKeyTable = TableQuery[ProjectApiKeyTable]
                def queryApiKey(deployment: ProjectApiKeyType, key: String, pId: DbRef[Project]) = {
                  val query = for {
                    k <- apiKeyTable if k.value === key && k.projectId === pId && k.keyType === deployment
                  } yield {
                    k.id
                  }
                  query.exists
                }

                val query = Query.apply(
                  (
                    queryApiKey(Deployment, formData.apiKey, project.id),
                    project.versions.exists(_.versionString === name)
                  )
                )

                EitherT
                  .liftF(service.runDBIO(query.result.head))
                  .ensure(Unauthorized(error("apiKey", "api.deploy.invalidKey")))(apiKeyExists => apiKeyExists._1)
                  .ensure(BadRequest(error("versionName", "api.deploy.versionExists")))(nameExists => !nameExists._2)
                  .semiflatMap(_ => project.owner.user)
                  .semiflatMap(user => user.toMaybeOrganization.semiflatMap(_.owner.user).getOrElse(user))
                  .flatMap { owner =>
                    val pluginUpload = this.factory
                      .getUploadError(owner)
                      .map(err => BadRequest(error("user", err)))
                      .toLeft(PluginUpload.bindFromRequest())
                      .flatMap(_.toRight(BadRequest(error("files", "error.noFile"))))

                    EitherT.fromEither[IO](pluginUpload).flatMap { data =>
                      this.factory
                        .processSubsequentPluginUpload(data, owner, project)
                        .leftMap(err => BadRequest(error("upload", err)))
                    }
                  }
                  .map { pendingVersion =>
                    pendingVersion.copy(
                      createForumPost = formData.createForumPost,
                      channelName = formChannel.name,
                      description = formData.changelog
                    )
                  }
                  .semiflatMap(_.complete(project, factory))
                  .semiflatMap {
                    case (newVersion, channel, tags) =>
                      val update =
                        if (formData.recommended)
                          service.update(
                            project.copy(
                              recommendedVersionId = Some(newVersion.id),
                              lastUpdated = new Timestamp(new Date().getTime)
                            )
                          )
                        else
                          service.update(project.copy(lastUpdated = new Timestamp(new Date().getTime)))

                      update.as(Created(api.writeVersion(newVersion, project, channel, None, tags)))
                  }
            }
            .merge
        case _ => IO.pure(NotFound)
      }
    }

  def listPages(version: String, pluginId: String, parentId: Option[DbRef[Page]]): Action[AnyContent] = Action.asyncF {
    version match {
      case "v1" => this.api.getPages(pluginId, parentId).value.map(ApiResult)
      case _    => IO.pure(NotFound)
    }
  }

  /**
    * Returns a JSON view of Ore Users.
    *
    * @param version  API version
    * @param limit    Amount of users to get
    * @param offset   Offset to drop
    * @return         List of users
    */
  def listUsers(version: String, limit: Option[Int], offset: Option[Int]): Action[AnyContent] = Action.asyncF {
    version match {
      case "v1" => this.api.getUserList(limit, offset).map(Ok(_))
      case _    => IO.pure(NotFound)
    }
  }

  /**
    * Returns a JSON view of the specified User.
    *
    * @param version    API version
    * @param username   Username of user
    * @return           User with username
    */
  def showUser(version: String, username: String): Action[AnyContent] = Action.asyncF {
    version match {
      case "v1" => this.api.getUser(username).map(ApiResult)
      case _    => IO.pure(NotFound)
    }
  }

  /**
    * Get the tags for a single version
    *
    * @param version     API Version
    * @param plugin      Plugin Id
    * @param versionName Version of the plugin
    * @return Tags for the version of the plugin
    */
  def listTags(version: String, plugin: String, versionName: String): Action[AnyContent] = Action.asyncF {
    version match {
      case "v1" => this.api.getTags(plugin, versionName).value.map(ApiResult)
      case _    => IO.pure(NotFound)
    }
  }

  def tagColor(version: String, id: String): Action[AnyContent] = Action {
    version match {
      case "v1" => ApiResult(this.api.getTagColor(id.toInt))
      case _    => NotFound
    }
  }

  /**
    * Returns a JSON statusz endpoint for Ore.
    *
    * @return statusz json
    */
  def showStatusZ: Action[AnyContent] = Action(Ok(this.status.json))

  def syncSso(): Action[AnyContent] = Action.asyncEitherT { implicit request =>
    val confApiKey = this.config.security.sso.apikey
    val confSecret = this.config.security.sso.secret

    Logger.debug("Sync Request received")

    forms.SyncSso
      .bindEitherT[IO](hasErrors => BadRequest(Json.obj("errors" -> hasErrors.errorsAsJson)))
      .ensure(BadRequest("API Key not valid"))(_._3 == confApiKey) //_3 is apiKey
      .ensure(BadRequest("Signature not matched"))(
        { case (ssoStr, sig, _) => CryptoUtils.hmac_sha256(confSecret, ssoStr.getBytes("UTF-8")) == sig }
      )
      .map(t => Uri.Query(Base64.getMimeDecoder.decode(t._1))) //_1 is sso
      .semiflatMap { q =>
        Logger.debug("Sync Payload: " + q)
        users.get(q.get("external_id").get.toLong).value.tupleLeft(q)
      }
      .semiflatMap {
        case (query, optUser) =>
          Logger.debug("Sync user found: " + optUser.isDefined)
          optUser
            .map { user =>
              val email      = query.get("email")
              val username   = query.get("username")
              val fullName   = query.get("name")
              val add_groups = query.get("add_groups")

              val globalRoles = add_groups.map { groups =>
                if (groups.trim.isEmpty) Nil
                else groups.split(",").flatMap(Role.withValueOpt).toList
              }

              val updateRoles = globalRoles.fold(IO.unit) { roles =>
                user.globalRoles.deleteAllFromParent(user) *> roles
                  .map(_.toDbRole)
                  .traverse(user.globalRoles.addAssoc(user, _))
                  .void
              }

              service.update(
                user.copy(
                  email = email.orElse(user.email),
                  name = username.getOrElse(user.name),
                  fullName = fullName.orElse(user.fullName)
                )
              ) *> updateRoles
            }
            .getOrElse(IO.unit)
            .as(Ok(Json.obj("status" -> "success")))
      }
  }
}
