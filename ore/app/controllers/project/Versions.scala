package controllers.project

import java.nio.file.Files._
import java.nio.file.{Files, StandardCopyOption}
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

import scala.annotation.unused

import play.api.i18n.{Lang, MessagesApi}
import play.api.mvc
import play.api.mvc.{Action, AnyContent, Result}
import play.filters.csrf.CSRF

import controllers.sugar.Requests.{AuthRequest, OreRequest, ProjectRequest}
import controllers.{OreBaseController, OreControllerComponents}
import ore.data.DownloadType
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{ProjectTable, UserTable, VersionTable}
import ore.db.{DbRef, Model}
import ore.markdown.MarkdownRenderer
import ore.models.admin.VersionVisibilityChange
import ore.models.project._
import ore.models.project.io.PluginFile
import ore.models.user.User
import ore.permission.Permission
import ore.rest.ApiV1ProjectsTable
import ore.util.OreMDC
import ore.{OreEnv, StatTracker}
import util.syntax._
import views.html.projects.{versions => views}

import _root_.io.circe.Json
import _root_.io.circe.syntax._
import cats.arrow.FunctionK
import cats.syntax.all._
import com.github.tminglei.slickpg.InetString
import com.typesafe.scalalogging
import zio.blocking.Blocking
import zio.interop.catz._
import zio.{IO, Task, UIO, ZIO}

/**
  * Controller for handling Version related actions.
  */
class Versions(stats: StatTracker[UIO])(
    implicit oreComponents: OreControllerComponents,
    messagesApi: MessagesApi,
    env: OreEnv,
    renderer: MarkdownRenderer
) extends OreBaseController {

  private val self = controllers.project.routes.Versions

  private val Logger    = scalalogging.Logger("Versions")
  private val MDCLogger = scalalogging.Logger.takingImplicit[OreMDC](Logger.underlying)

  def showLog(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated
      .andThen(PermissionAction[AuthRequest](Permission.ViewLogs))
      .andThen(ProjectAction(author, slug))
      .asyncF { implicit request =>
        for {
          version <- getVersion(request.project, versionString)
          visChanges <- service.runDBIO(
            version
              .visibilityChangesByDate(ModelView.raw(VersionVisibilityChange))
              .joinLeft(TableQuery[UserTable])
              .on(_.createdBy === _.id)
              .result
          )
        } yield {
          Ok(
            views.log(
              request.project,
              version,
              Model.unwrapNested[Seq[(Model[VersionVisibilityChange], Option[User])]](visChanges)
            )
          )
        }
      }
  }

  /**
    * Sends the specified Project Version to the client.
    *
    * @param author        Project owner
    * @param slug          Project slug
    * @param versionString Version string
    * @return Sent file
    */
  def download(
      author: String,
      slug: String,
      versionString: String,
      token: Option[String],
      confirm: Boolean
  ): Action[AnyContent] =
    ProjectAction(author, slug).asyncF { implicit request =>
      val project = request.project
      getVersion(project, versionString).flatMap(sendVersion(project, _, token, confirm))
    }

  private def sendVersion(project: Project, version: Model[Version], token: Option[String], confirm: Boolean)(
      implicit req: ProjectRequest[_]
  ): UIO[Result] = {
    checkConfirmation(version, token).flatMap { passed =>
      if (passed || confirm)
        _sendVersion(project, version)
      else
        UIO.succeed(
          Redirect(
            self.showDownloadConfirm(
              project.ownerName,
              project.slug,
              version.name,
              Some(DownloadType.UploadedFile.value),
              api = Some(false),
              Some("dummy")
            )
          )
        )
    }
  }

  private def checkConfirmation(version: Model[Version], token: Option[String])(
      implicit req: ProjectRequest[_]
  ): UIO[Boolean] = {
    if (!oreComponents.config.ore.projects.warnOnUnsafeDownloads || version.reviewState == ReviewState.Reviewed)
      UIO.succeed(true)
    else {
      val hasSessionConfirm = req.session.get(DownloadWarning.cookieKey(version.id)).contains("confirmed")

      if (hasSessionConfirm) {
        UIO.succeed(true)
      } else {
        // check confirmation for API
        val withError = for {
          tkn <- ZIO.fromOption(token)
          warn <- ModelView
            .now(DownloadWarning)
            .find { warn =>
              (warn.token === tkn) &&
              (warn.versionId === version.id.value) &&
              (warn.address === InetString(StatTracker.remoteAddress)) &&
              warn.isConfirmed
            }
            .toZIO
          res <- if (warn.hasExpired) service.delete(warn).as(false) else UIO.succeed(true)
        } yield res

        withError.catchAll(_ => UIO.succeed(false))
      }
    }
  }

  private def _sendVersion(project: Project, version: Model[Version])(implicit req: ProjectRequest[_]): UIO[Result] =
    this.stats.versionDownloaded(version) {
      UIO.succeed {
        Ok.sendPath(
          projectFiles
            .getVersionDir(project.ownerName, project.name, version.name)
            .resolve(version.fileName)
        )
      }
    }

  private val MultipleChoices = new Status(MULTIPLE_CHOICES)

  /**
    * Displays a confirmation view for downloading unreviewed versions. The
    * client is issued a unique token that will be checked once downloading to
    * ensure that they have landed on this confirmation before downloading the
    * version.
    *
    * @param author Project author
    * @param slug   Project slug
    * @param target Target version
    * @param dummy  A parameter to get around Chrome's cache
    * @return       Confirmation view
    */
  def showDownloadConfirm(
      author: String,
      slug: String,
      target: String,
      downloadType: Option[Int],
      api: Option[Boolean],
      @unused dummy: Option[String]
  ): Action[AnyContent] = {
    ProjectAction(author, slug).asyncF { implicit request =>
      val dlType              = downloadType.flatMap(DownloadType.withValueOpt).getOrElse(DownloadType.UploadedFile)
      implicit val lang: Lang = request.lang
      val project             = request.project
      getVersion(project, target)
        .ensure(Redirect(ShowProject(author, slug)).withError("error.plugin.stateChanged"))(
          _.reviewState != ReviewState.Reviewed
        )
        .flatMap { version =>
          // generate a unique "warning" object to ensure the user has landed
          // on the warning before downloading
          val token = UUID.randomUUID().toString
          val expiration =
            OffsetDateTime.now().plus(this.config.ore.projects.unsafeDownloadMaxAge.toMillis, ChronoUnit.MILLIS)
          val address = InetString(StatTracker.remoteAddress)
          // remove old warning attached to address that are expired (or duplicated for version)
          val removeWarnings = service.deleteWhere(DownloadWarning) { warning =>
            (warning.address === address || warning.expiration < OffsetDateTime
              .now()) && warning.versionId === version.id.value
          }
          // create warning
          val addWarning = service.insert(
            DownloadWarning(
              expiration = expiration,
              token = token,
              versionId = version.id,
              address = address,
              downloadId = None
            )
          )

          val isPartial   = version.reviewState == ReviewState.PartiallyReviewed
          val apiMsgKey   = if (isPartial) "version.download.confirmPartial.api" else "version.download.confirm.body.api"
          lazy val apiMsg = this.messagesApi(apiMsgKey)

          lazy val curlInstruction = CSRF.getToken match {
            case Some(value) =>
              this.messagesApi(
                "version.download.confirm.curl",
                self.confirmDownload(author, slug, target, Some(dlType.value), Some(token), None).absoluteURL(),
                value.value
              )
            case None =>
              this.messagesApi(
                "version.download.confirm.curl.nocsrf",
                self.confirmDownload(author, slug, target, Some(dlType.value), Some(token), None).absoluteURL()
              )
          }

          if (api.getOrElse(false)) {
            (removeWarnings *> addWarning).as(
              MultipleChoices(
                Json
                  .obj(
                    "message" := apiMsg,
                    "post" := self
                      .confirmDownload(author, slug, target, Some(dlType.value), Some(token), None)
                      .absoluteURL(),
                    "url" := self.downloadJarById(project.pluginId, version.name, Some(token)).absoluteURL(),
                    "curl" := curlInstruction,
                    "token" := token
                  )
                  .spaces4
              ).withHeaders(CONTENT_DISPOSITION -> "inline; filename=\"README.txt\"").as("application/json")
            )
          } else {
            val userAgent = request.headers.get("User-Agent").map(_.toLowerCase)

            if (userAgent.exists(_.startsWith("wget/"))) {
              IO.succeed(
                MultipleChoices(this.messagesApi("version.download.confirm.wget"))
                  .withHeaders(CONTENT_DISPOSITION -> "inline; filename=\"README.txt\"")
              )
            } else if (userAgent.exists(_.startsWith("curl/"))) {
              (removeWarnings *> addWarning).as(
                MultipleChoices(
                  apiMsg + "\n" + curlInstruction + "\n"
                ).withHeaders(CONTENT_DISPOSITION -> "inline; filename=\"README.txt\"")
              )
            } else {
              val nonReviewed = version.tags.stability != Version.Stability.Stable

              //We return Ok here to make sure Chrome sets the cookie
              //https://bugs.chromium.org/p/chromium/issues/detail?id=696204
              IO.succeed(
                Ok(views.unsafeDownload(project, version, nonReviewed, dlType))
                  .addingToSession(DownloadWarning.cookieKey(version.id) -> "set")
              )
            }
          }
        }
    }
  }

  def confirmDownload(
      author: String,
      slug: String,
      target: String,
      downloadType: Option[Int],
      token: Option[String],
      @unused dummy: Option[String] //A parameter to get around Chrome's cache
  ): Action[AnyContent] = {
    ProjectAction(author, slug).asyncF { implicit request =>
      getVersion(request.data.project, target)
        .ensure(Redirect(ShowProject(author, slug)).withError("error.plugin.stateChanged"))(
          _.reviewState != ReviewState.Reviewed
        )
        .flatMap { version =>
          confirmDownload0(version.id, downloadType, token)
            .orElseFail(Redirect(ShowProject(author, slug)).withError("error.plugin.noConfirmDownload"))
        }
        .map {
          case (dl, optNewSession) =>
            val newSession = optNewSession.getOrElse(request.session)
            dl.downloadType match {
              case DownloadType.UploadedFile =>
                Redirect(self.download(author, slug, target, token)).withSession(newSession)
              case DownloadType.JarFile =>
                Redirect(self.downloadJar(author, slug, target, token)).withSession(newSession)
            }
        }
    }
  }

  /**
    * Confirms the download and prepares the unsafe download.
    */
  private def confirmDownload0(versionId: DbRef[Version], downloadType: Option[Int], optToken: Option[String])(
      implicit request: OreRequest[_]
  ): IO[Unit, (Model[UnsafeDownload], Option[mvc.Session])] = {
    val addr = InetString(StatTracker.remoteAddress)
    val dlType = downloadType
      .flatMap(DownloadType.withValueOpt)
      .getOrElse(DownloadType.UploadedFile)

    val user = request.currentUser

    val insertDownload = service.insert(
      UnsafeDownload(userId = user.map(_.id.value), address = addr, downloadType = dlType)
    )

    optToken match {
      case None =>
        val cookieKey    = DownloadWarning.cookieKey(versionId)
        val sessionIsSet = request.session.get(cookieKey).contains("set")

        if (sessionIsSet) {
          val newSession = request.session + (cookieKey -> "confirmed")
          insertDownload.tupleRight(Some(newSession))
        } else {
          IO.fail(())
        }
      case Some(token) =>
        // find warning
        ModelView
          .now(DownloadWarning)
          .find { warn =>
            (warn.address === addr) &&
            (warn.token === token) &&
            (warn.versionId === versionId) &&
            !warn.isConfirmed &&
            warn.downloadId.?.isEmpty
          }
          .toZIO
          .flatMap(warn => if (warn.hasExpired) service.delete(warn) *> IO.fail(()) else IO.succeed(warn))
          .flatMap { warn =>
            // warning confirmed and redirect to download
            for {
              unsafeDownload <- insertDownload
              _              <- service.update(warn)(_.copy(isConfirmed = true, downloadId = Some(unsafeDownload.id)))
            } yield (unsafeDownload, None)
          }
    }
  }

  /**
    * Sends the specified project's current recommended version to the client.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Sent file
    */
  def downloadRecommended(author: String, slug: String, token: Option[String]): Action[AnyContent] =
    ProjectAction(author, slug).asyncF { implicit request =>
      service
        .runDBIO(firstPromotedVersion(request.project.id).result.headOption)
        .get
        .orElseFail(NotFound)
        .flatMap(sendVersion(request.project, _, token, confirm = false))
    }

  private def firstPromotedVersion(id: DbRef[Project]) =
    for {
      hp <- TableQuery[ApiV1ProjectsTable]
      p  <- TableQuery[ProjectTable] if hp.id === p.id
      v  <- TableQuery[VersionTable]
      if hp.id === id
      if p.id === v.projectId && v.versionString === ((hp.promotedVersions ~> 0) +>> "version_string")
    } yield v

  /**
    * Downloads the specified version as a JAR regardless of the original
    * uploaded file type.
    *
    * @param author         Project owner
    * @param slug           Project slug
    * @param versionString  Version name
    * @return               Sent file
    */
  def downloadJar(author: String, slug: String, versionString: String, token: Option[String]): Action[AnyContent] =
    ProjectAction(author, slug).asyncF { implicit request =>
      getVersion(request.project, versionString).flatMap(sendJar(request.project, _, token))
    }

  private def sendJar(
      project: Model[Project],
      version: Model[Version],
      token: Option[String],
      api: Boolean = false
  )(
      implicit request: ProjectRequest[_]
  ): ZIO[Blocking, Result, Result] = {
    if (project.visibility == Visibility.SoftDelete) {
      IO.fail(NotFound)
    } else {
      checkConfirmation(version, token).flatMap { passed =>
        if (!passed) {
          IO.succeed(
            Redirect(
              self.showDownloadConfirm(
                project.ownerName,
                project.slug,
                version.name,
                Some(DownloadType.JarFile.value),
                api = Some(api),
                None
              )
            )
          )
        } else {
          val fileName = version.fileName
          val path     = projectFiles.getVersionDir(project.ownerName, project.name, version.name).resolve(fileName)
          project.user[Task].orDie.flatMap { projectOwner =>
            import cats.tagless._
            val newStats: StatTracker[RIO[Blocking, *]] = InvariantK[StatTracker].imapK(stats) {
              new FunctionK[UIO, RIO[Blocking, *]] {
                override def apply[A](fa: UIO[A]): RIO[Blocking, A] = fa
              }
            } {
              new FunctionK[RIO[Blocking, *], UIO] {
                override def apply[A](fa: RIO[Blocking, A]): UIO[A] = fa.provide(zioRuntime.environment)
              }
            }

            newStats.versionDownloaded(version) {
              if (fileName.endsWith(".jar"))
                IO.succeed(Ok.sendPath(path))
              else {
                val pluginFile = new PluginFile(path, path.getFileName.toString, projectOwner)
                val jarName    = fileName.substring(0, fileName.lastIndexOf('.')) + ".jar"
                val jarPath    = env.tmp.resolve(project.ownerName).resolve(jarName)

                import zio.blocking._
                pluginFile
                  .newJarStream[ZIO[Blocking, Throwable, *]]
                  .use { jarIn =>
                    jarIn
                      .fold(
                        e => Task.fail(new Exception(e)),
                        is => effectBlocking(copy(is, jarPath, StandardCopyOption.REPLACE_EXISTING))
                      )
                      .unit
                  }
                  .tapError(e => IO(MDCLogger.error("an error occurred while trying to send a plugin", e)))
                  .orDie
                  .as(Ok.sendPath(jarPath, onClose = () => Files.delete(jarPath)))
              }
            }
          }
        }
      }
    }

  }

  /**
    * Downloads the Project's recommended version as a JAR regardless of the
    * original uploaded file type.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Sent file
    */
  def downloadRecommendedJar(author: String, slug: String, token: Option[String]): Action[AnyContent] = {
    ProjectAction(author, slug).asyncF { implicit request =>
      service
        .runDBIO(firstPromotedVersion(request.project.id).result.headOption)
        .get
        .orElseFail(NotFound)
        .flatMap(sendJar(request.project, _, token))
    }
  }

  /**
    * Downloads the specified version as a JAR regardless of the original
    * uploaded file type.
    *
    * @param pluginId       Project unique plugin ID
    * @param versionString  Version name
    * @return               Sent file
    */
  def downloadJarById(pluginId: String, versionString: String, optToken: Option[String]): Action[AnyContent] = {
    ProjectAction(pluginId).asyncF { implicit request =>
      val project = request.project
      getVersion(project, versionString).flatMap { version =>
        optToken
          .map { token =>
            confirmDownload0(version.id, Some(DownloadType.JarFile.value), Some(token)).orElseFail(notFound) *>
              sendJar(project, version, optToken, api = true)
          }
          .getOrElse(sendJar(project, version, optToken, api = true))
      }
    }
  }

  /**
    * Downloads the Project's recommended version as a JAR regardless of the
    * original uploaded file type.
    *
    * @param pluginId Project unique plugin ID
    * @return         Sent file
    */
  def downloadRecommendedJarById(pluginId: String, token: Option[String]): Action[AnyContent] = {
    ProjectAction(pluginId).asyncF { implicit request =>
      service
        .runDBIO(firstPromotedVersion(request.project.id).result.headOption)
        .get
        .orElseFail(NotFound)
        .flatMap(sendJar(request.project, _, token, api = true))
    }
  }
}
