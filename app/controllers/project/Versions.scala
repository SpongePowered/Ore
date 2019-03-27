package controllers.project

import java.nio.file.Files._
import java.nio.file.{Files, StandardCopyOption}
import java.sql.Timestamp
import java.util.{Date, UUID}
import javax.inject.Inject

import scala.concurrent.ExecutionContext

import play.api.cache.AsyncCacheApi
import play.api.i18n.{Lang, MessagesApi}
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, Request, Result}
import play.filters.csrf.CSRF

import controllers.OreBaseController
import controllers.sugar.Bakery
import controllers.sugar.Requests.{AuthRequest, OreRequest, ProjectRequest}
import db.access.ModelView
import db.impl.OrePostgresDriver.api._
import db.impl.schema.UserTable
import db.{Model, DbRef, ModelService}
import discourse.OreDiscourseApi
import form.OreForms
import models.admin.VersionVisibilityChange
import models.project._
import models.user.{LoggedAction, User, UserActionLogger}
import models.viewhelper.VersionData
import ore.permission.{EditVersions, HardRemoveVersion, ReviewProjects, UploadVersions, ViewLogs}
import ore.project.factory.{PendingProject, ProjectFactory}
import ore.project.io.DownloadType._
import ore.project.io.{DownloadType, PluginFile, PluginUpload}
import ore.{OreConfig, OreEnv, StatTracker}
import security.spauth.{SingleSignOnConsumer, SpongeAuthApi}
import util.OreMDC
import util.StringUtils._
import util.syntax._
import views.html.projects.{versions => views}

import cats.data.{EitherT, OptionT}
import cats.effect.IO
import cats.syntax.all._
import cats.instances.option._
import com.github.tminglei.slickpg.InetString
import com.typesafe.scalalogging

/**
  * Controller for handling Version related actions.
  */
class Versions @Inject()(stats: StatTracker, forms: OreForms, factory: ProjectFactory)(
    implicit val ec: ExecutionContext,
    auth: SpongeAuthApi,
    bakery: Bakery,
    sso: SingleSignOnConsumer,
    cache: AsyncCacheApi,
    messagesApi: MessagesApi,
    env: OreEnv,
    config: OreConfig,
    service: ModelService,
    forums: OreDiscourseApi
) extends OreBaseController {

  private val fileManager = projects.fileManager
  private val self        = controllers.project.routes.Versions

  private val Logger    = scalalogging.Logger("Versions")
  private val MDCLogger = scalalogging.Logger.takingImplicit[OreMDC](Logger.underlying)

  private def VersionEditAction(author: String, slug: String) =
    AuthedProjectAction(author, slug, requireUnlock = true).andThen(ProjectPermissionAction(EditVersions))

  private def VersionUploadAction(author: String, slug: String) =
    AuthedProjectAction(author, slug, requireUnlock = true).andThen(ProjectPermissionAction(UploadVersions))

  /**
    * Shows the specified version view page.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param versionString Version name
    * @return Version view
    */
  def show(author: String, slug: String, versionString: String): Action[AnyContent] =
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      for {
        version <- getVersion(request.project, versionString)
        data    <- EitherT.right[Result](VersionData.of(request, version))
        response <- EitherT.right[Result](
          this.stats.projectViewed(Ok(views.view(data, request.scoped)))
        )
      } yield response
    }

  /**
    * Saves the specified Version's description.
    *
    * @param author        Project owner
    * @param slug          Project slug
    * @param versionString Version name
    * @return View of Version
    */
  def saveDescription(author: String, slug: String, versionString: String): Action[String] = {
    VersionEditAction(author, slug).asyncEitherT(parse.form(forms.VersionDescription)) { implicit request =>
      for {
        version <- getVersion(request.project, versionString)
        oldDescription = version.description.getOrElse("")
        newDescription = request.body.trim
        _ <- EitherT.right[Result](version.updateDescriptionWithForum(request.project, newDescription))
        _ <- EitherT.right[Result](
          UserActionLogger.log(
            request.request,
            LoggedAction.VersionDescriptionEdited,
            version.id,
            newDescription,
            oldDescription
          )
        )
      } yield Redirect(self.show(author, slug, versionString))
    }
  }

  /**
    * Sets the specified Version as the recommended download.
    *
    * @param author         Project owner
    * @param slug           Project slug
    * @param versionString  Version name
    * @return               View of version
    */
  def setRecommended(author: String, slug: String, versionString: String): Action[AnyContent] = {
    VersionEditAction(author, slug).asyncEitherT { implicit request =>
      for {
        version <- getVersion(request.project, versionString)
        _ <- EitherT.right[Result](
          service.update(request.project)(_.copy(recommendedVersionId = Some(version.id)))
        )
        _ <- EitherT.right[Result](
          UserActionLogger.log(
            request.request,
            LoggedAction.VersionAsRecommended,
            version.id,
            "recommended version",
            "listed version"
          )
        )
      } yield Redirect(self.show(author, slug, versionString))
    }
  }

  /**
    * Sets the specified Version as approved by the moderation staff.
    *
    * @param author         Project owner
    * @param slug           Project slug
    * @param versionString  Version name
    * @return               View of version
    */
  def approve(author: String, slug: String, versionString: String, partial: Boolean): Action[AnyContent] = {
    AuthedProjectAction(author, slug, requireUnlock = true)
      .andThen(ProjectPermissionAction(ReviewProjects))
      .asyncEitherT { implicit request =>
        val newState = if (partial) ReviewState.PartiallyReviewed else ReviewState.Reviewed
        for {
          version <- getVersion(request.data.project, versionString)
          _ <- EitherT.right[Result](
            service.update(version)(
              _.copy(
                reviewState = newState,
                reviewerId = Some(request.user.id),
                approvedAt = Some(service.theTime)
              )
            )
          )
          _ <- EitherT.right[Result](
            UserActionLogger.log(
              request.request,
              LoggedAction.VersionReviewStateChanged,
              version.id,
              newState.toString,
              version.reviewState.toString,
            )
          )
        } yield Redirect(self.show(author, slug, versionString))
      }
  }

  /**
    * Displays the "versions" tab within a Project view.
    *
    * @param author   Owner of project
    * @param slug     Project slug
    * @param channels Visible channels
    * @return View of project
    */
  def showList(author: String, slug: String, channels: Option[String]): Action[AnyContent] = {
    ProjectAction(author, slug).asyncF { implicit request =>
      val dbio = for {
        allChannels <- request.project.channels(ModelView.raw(Channel)).result
        visibleNames = channels.fold(allChannels.map(_.name.toLowerCase))(_.toLowerCase.split(',').toSeq)
        visible      = allChannels.filter(ch => visibleNames.contains(ch.name.toLowerCase))
        visibleIds   = visible.map(_.id.value)
        versionCount <- request.project
          .versions(ModelView.later(Version))
          .count { v =>
            val inChannel = v.channelId.inSetBind(visibleIds)
            val isVisible =
              if (request.headerData.globalPerm(ReviewProjects)) true: Rep[Boolean]
              else v.visibility === (Visibility.Public: Visibility)
            inChannel && isVisible
          }
          .result
      } yield {
        val visibleNamesForView = if (visibleNames == allChannels.map(_.name.toLowerCase)) Nil else visibleNames

        (allChannels, versionCount, visibleNamesForView)
      }

      service.runDBIO(dbio).flatMap {
        case (allChannels, versionCount, visibleNamesForView) =>
          this.stats
            .projectViewed(
              Ok(
                views.list(
                  request.data,
                  request.scoped,
                  Model.unwrapNested(allChannels),
                  versionCount,
                  visibleNamesForView
                )
              )
            )
      }
    }
  }

  /**
    * Shows the creation form for new versions on projects.
    *
    * @param author Owner of project
    * @param slug   Project slug
    * @return Version creation view
    */
  def showCreator(author: String, slug: String): Action[AnyContent] =
    VersionUploadAction(author, slug).asyncF { implicit request =>
      service.runDBIO(request.project.channels(ModelView.raw(Channel)).result).map { channels =>
        val project = request.project
        Ok(
          views.create(
            project.name,
            project.slug,
            project.ownerName,
            project.description,
            isProjectPending = false,
            forumSync = request.data.settings.forumSync,
            None,
            Some(Model.unwrapNested(channels)),
            showFileControls = true
          )
        )
      }
    }

  /**
    * Uploads a new version for a project for further processing.
    *
    * @param author Owner name
    * @param slug   Project slug
    * @return Version create page (with meta)
    */
  def upload(author: String, slug: String): Action[AnyContent] = VersionUploadAction(author, slug).asyncEitherT {
    implicit request =>
      val call = self.showCreator(author, slug)
      val user = request.user

      val uploadData = this.factory
        .getUploadError(user)
        .map(error => Redirect(call).withError(error))
        .toLeft(())
        .flatMap(_ => PluginUpload.bindFromRequest().toRight(Redirect(call).withError("error.noFile")))

      EitherT
        .fromEither[IO](uploadData)
        .flatMap { data =>
          this.factory
            .processSubsequentPluginUpload(data, user, request.data.project)
            .leftMap(err => Redirect(call).withError(err))
        }
        .semiflatMap { pendingVersion =>
          pendingVersion
            .copy(authorId = user.id)
            .cache
            .as(
              Redirect(
                self.showCreatorWithMeta(request.data.project.ownerName, slug, pendingVersion.versionString)
              )
            )
        }
  }

  /**
    * Displays the "version create" page with the associated plugin meta-data.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param versionString Version name
    * @return Version create view
    */
  def showCreatorWithMeta(author: String, slug: String, versionString: String): Action[AnyContent] =
    UserLock(ShowProject(author, slug)).asyncF { implicit request =>
      val success = OptionT
        .fromOption[IO](this.factory.getPendingVersion(author, slug, versionString))
        // Get pending version
        .flatMap(pendingVersion => pendingOrReal(author, slug).map(pendingVersion -> _))
        .semiflatMap {
          case (pendingVersion, Left(pending)) =>
            val projectData =
              (pending.name, pending.slug, pending.ownerName, pending.description, true, pending.settings.forumSync)
            IO.pure((None, projectData, pendingVersion))
          case (pendingVersion, Right(real)) =>
            val projectData = real.settings.map { settings =>
              (real.name, real.slug, real.ownerName, real.description, false, settings.forumSync)
            }
            (service.runDBIO(real.channels(ModelView.raw(Channel)).result), projectData)
              .parMapN((channels, data) => (Some(channels), data, pendingVersion))
        }
        .map {
          case (
              channels,
              (projectName, projectSlug, ownerName, projectDescription, isPending, forumSync),
              pendingVersion
              ) =>
            Ok(
              views.create(
                projectName,
                projectSlug,
                ownerName,
                projectDescription,
                isPending,
                forumSync,
                Some(pendingVersion),
                Model.unwrapNested(channels),
                showFileControls = channels.isDefined
              )
            )
        }

      success.getOrElse(Redirect(self.showCreator(author, slug)).withError("error.plugin.timeout"))
    }

  private def pendingOrReal(author: String, slug: String): OptionT[IO, Either[PendingProject, Model[Project]]] =
    // Returns either a PendingProject or existing Project
    projects
      .withSlug(author, slug)
      .map[Either[PendingProject, Model[Project]]](Right.apply)
      .orElse(OptionT.fromOption[IO](this.factory.getPendingProject(author, slug)).map(Left.apply))

  /**
    * Completes the creation of the specified pending version or project if
    * first version.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param versionString Version name
    * @return New version view
    */
  def publish(author: String, slug: String, versionString: String): Action[AnyContent] = {
    UserLock(ShowProject(author, slug)).asyncF { implicit request =>
      // First get the pending Version
      this.factory.getPendingVersion(author, slug, versionString) match {
        case None =>
          // Not found
          IO.pure(Redirect(self.showCreator(author, slug)).withError("error.plugin.timeout"))
        case Some(pendingVersion) =>
          // Get submitted channel
          this.forms.VersionCreate.bindFromRequest.fold(
            // Invalid channel
            FormError(self.showCreatorWithMeta(author, slug, versionString)).andThen(IO.pure),
            versionData => {
              // Channel is valid

              val newPendingVersion = pendingVersion.copy(
                channelName = versionData.channelName.trim,
                channelColor = versionData.color,
                createForumPost = versionData.forumPost,
                description = versionData.content
              )

              // Check for pending project
              this.factory.getPendingProject(author, slug) match {
                case None =>
                  // No pending project, create version for existing project
                  getProject(author, slug).flatMap {
                    project =>
                      project
                        .channels(ModelView.now(Channel))
                        .find(equalsIgnoreCase(_.name, newPendingVersion.channelName))
                        .toRight(versionData.addTo(project))
                        .leftFlatMap(identity)
                        .semiflatMap {
                          _ =>
                            newPendingVersion
                              .complete(project, factory)
                              .map(_._1)
                              .flatTap { newVersion =>
                                if (versionData.recommended)
                                  service
                                    .update(project)(
                                      _.copy(
                                        recommendedVersionId = Some(newVersion.id),
                                        lastUpdated = new Timestamp(new Date().getTime)
                                      )
                                    )
                                    .void
                                else
                                  service
                                    .update(project)(
                                      _.copy(
                                        lastUpdated = new Timestamp(new Date().getTime)
                                      )
                                    )
                                    .void
                              }
                              .flatTap(addUnstableTag(_, versionData.unstable))
                              .flatTap { newVersion =>
                                UserActionLogger.log(
                                  request,
                                  LoggedAction.VersionUploaded,
                                  newVersion.id,
                                  "published",
                                  "null"
                                )
                              }
                              .as(Redirect(self.show(author, slug, versionString)))
                        }
                        .leftMap(Redirect(self.showCreatorWithMeta(author, slug, versionString)).withErrors(_))
                  }.merge
                case Some(pendingProject) =>
                  // Found a pending project, create it with first version
                  pendingProject
                    .complete(factory)
                    .flatTap { created =>
                      UserActionLogger.log(request, LoggedAction.ProjectCreated, created._1.id, "created", "null")
                    }
                    .flatTap(created => addUnstableTag(created._2, versionData.unstable))
                    .productR(projects.refreshHomePage(MDCLogger))
                    .as(Redirect(ShowProject(author, slug)))
              }
            }
          )
      }
    }
  }

  private def addUnstableTag(version: Model[Version], unstable: Boolean) = {
    if (unstable) {
      service
        .insert(
          VersionTag(
            versionId = version.id,
            name = "Unstable",
            data = "",
            color = TagColor.Unstable
          )
        )
        .void
    } else IO.unit
  }

  /**
    * Deletes the specified version and returns to the version page.
    *
    * @param author        Owner name
    * @param slug          Project slug
    * @param versionString Version name
    * @return Versions page
    */
  def delete(author: String, slug: String, versionString: String): Action[String] = {
    Authenticated
      .andThen(PermissionAction[AuthRequest](HardRemoveVersion))
      .asyncEitherT(parse.form(forms.NeedsChanges)) { implicit request =>
        val comment = request.body
        getProjectVersion(author, slug, versionString)
          .semiflatMap(version => projects.deleteVersion(version).as(version))
          .semiflatMap { version =>
            UserActionLogger
              .log(
                request,
                LoggedAction.VersionDeleted,
                version.id,
                s"Deleted: $comment",
                s"$version.visibility"
              )
          }
          .map(_ => Redirect(self.showList(author, slug, None)))
      }
  }

  /**
    * Soft deletes the specified version.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Home page
    */
  def softDelete(author: String, slug: String, versionString: String): Action[String] =
    VersionEditAction(author, slug).asyncEitherT(parse.form(forms.NeedsChanges)) { implicit request =>
      val comment = request.body
      getVersion(request.project, versionString)
        .semiflatMap(version => projects.prepareDeleteVersion(version).as(version))
        .semiflatMap { version =>
          version.setVisibility(Visibility.SoftDelete, comment, request.user.id).as(version)
        }
        .semiflatMap { version =>
          UserActionLogger
            .log(request.request, LoggedAction.VersionDeleted, version.id, s"SoftDelete: $comment", "")
        }
        .map(_ => Redirect(self.showList(author, slug, None)))
    }

  /**
    * Restore the specified version.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Home page
    */
  def restore(author: String, slug: String, versionString: String): Action[String] = {
    Authenticated.andThen(PermissionAction[AuthRequest](ReviewProjects)).asyncEitherT(parse.form(forms.NeedsChanges)) {
      implicit request =>
        val comment = request.body
        getProjectVersion(author, slug, versionString)
          .semiflatMap(version => version.setVisibility(Visibility.Public, comment, request.user.id).as(version))
          .semiflatMap { version =>
            UserActionLogger.log(request, LoggedAction.VersionDeleted, version.id, s"Restore: $comment", "")
          }
          .map(_ => Redirect(self.showList(author, slug, None)))
    }
  }

  def showLog(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction[AuthRequest](ViewLogs)).andThen(ProjectAction(author, slug)).asyncEitherT {
      implicit request =>
        for {
          version <- getVersion(request.project, versionString)
          visChanges <- EitherT.right[Result](
            service.runDBIO(
              version
                .visibilityChangesByDate(ModelView.raw(VersionVisibilityChange))
                .joinLeft(TableQuery[UserTable])
                .on(_.createdBy === _.id)
                .result
            )
          )
        } yield
          Ok(
            views.log(
              request.project,
              version,
              Model.unwrapNested[Seq[(Model[VersionVisibilityChange], Option[User])]](visChanges)
            )
          )
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
  def download(author: String, slug: String, versionString: String, token: Option[String]): Action[AnyContent] =
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      val project = request.project
      getVersion(project, versionString).semiflatMap(sendVersion(project, _, token))
    }

  private def sendVersion(project: Project, version: Model[Version], token: Option[String])(
      implicit req: ProjectRequest[_]
  ): IO[Result] = {
    checkConfirmation(version, token).flatMap { passed =>
      if (passed)
        _sendVersion(project, version)
      else
        IO.pure(
          Redirect(
            self.showDownloadConfirm(
              project.ownerName,
              project.slug,
              version.name,
              Some(UploadedFile.value),
              api = Some(false)
            )
          )
        )
    }
  }

  private def checkConfirmation(version: Model[Version], token: Option[String])(
      implicit req: ProjectRequest[_]
  ): IO[Boolean] = {
    if (version.reviewState == ReviewState.Reviewed)
      IO.pure(true)
    else {
      // check for confirmation
      OptionT
        .fromOption[IO](req.cookies.get(DownloadWarning.COOKIE + "_" + version.id).map(_.value).orElse(token))
        .flatMap { tkn =>
          ModelView.now(DownloadWarning).find { warn =>
            (warn.token === tkn) &&
            (warn.versionId === version.id.value) &&
            (warn.address === InetString(StatTracker.remoteAddress)) &&
            warn.isConfirmed
          }
        }
        .semiflatMap(warn => if (warn.hasExpired) service.delete(warn).as(false) else IO.pure(true))
        .exists(identity)
    }
  }

  private def _sendVersion(project: Project, version: Model[Version])(implicit req: ProjectRequest[_]): IO[Result] =
    this.stats.versionDownloaded(version) {
      IO.pure {
        Ok.sendPath(
          this.fileManager
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
    * @return       Confirmation view
    */
  def showDownloadConfirm(
      author: String,
      slug: String,
      target: String,
      downloadType: Option[Int],
      api: Option[Boolean]
  ): Action[AnyContent] = {
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      val dlType              = downloadType.flatMap(DownloadType.withValueOpt).getOrElse(DownloadType.UploadedFile)
      implicit val lang: Lang = request.lang
      val project             = request.project
      getVersion(project, target)
        .ensure(Redirect(ShowProject(author, slug)).withError("error.plugin.stateChanged"))(
          _.reviewState != ReviewState.Reviewed
        )
        .semiflatMap { version =>
          // generate a unique "warning" object to ensure the user has landed
          // on the warning before downloading
          val token      = UUID.randomUUID().toString
          val expiration = new Timestamp(new Date().getTime + this.config.security.unsafeDownloadMaxAge)
          val address    = InetString(StatTracker.remoteAddress)
          // remove old warning attached to address that are expired (or duplicated for version)
          val removeWarnings = service.deleteWhere(DownloadWarning) { warning =>
            (warning.address === address && warning.expiration < new Timestamp(new Date().getTime)) || warning.versionId === version.id.value
          }
          // create warning
          val addWarning = service.insert(
            DownloadWarning(
              expiration = expiration,
              token = token,
              versionId = version.id,
              address = InetString(StatTracker.remoteAddress),
              downloadId = None
            )
          )

          val isPartial = version.reviewState == ReviewState.PartiallyReviewed
          val apiMsg =
            if (isPartial) "version.download.confirmPartial.body.api" else "version.download.confirm.body.api"

          if (api.getOrElse(false)) {
            (removeWarnings *> addWarning).as {
              MultipleChoices(
                Json.obj(
                  "message" -> this.messagesApi(apiMsg).split('\n'),
                  "post"    -> self.confirmDownload(author, slug, target, Some(dlType.value), token).absoluteURL(),
                  "url"     -> self.downloadJarById(project.pluginId, version.name, Some(token)).absoluteURL(),
                  "token"   -> token
                )
              )
            }
          } else {
            val userAgent = request.headers.get("User-Agent").map(_.toLowerCase)

            if (userAgent.exists(_.startsWith("wget/"))) {
              IO.pure(
                MultipleChoices(this.messagesApi("version.download.confirm.wget"))
                  .withHeaders("Content-Disposition" -> "inline; filename=\"README.txt\"")
              )
            } else if (userAgent.exists(_.startsWith("curl/"))) {
              IO.pure(
                MultipleChoices(
                  this.messagesApi(
                    apiMsg,
                    self.confirmDownload(author, slug, target, Some(dlType.value), token).absoluteURL(),
                    CSRF.getToken.get.value
                  ) + "\n"
                ).withHeaders("Content-Disposition" -> "inline; filename=\"README.txt\"")
              )
            } else {
              (removeWarnings *> addWarning, version.channel.map(_.isNonReviewed)).parMapN { (warn, nonReviewed) =>
                MultipleChoices(views.unsafeDownload(project, version, nonReviewed, dlType, token))
                  .withCookies(warn.cookie)
              }
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
      token: String
  ): Action[AnyContent] = {
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      getVersion(request.data.project, target)
        .ensure(Redirect(ShowProject(author, slug)).withError("error.plugin.stateChanged"))(
          _.reviewState != ReviewState.Reviewed
        )
        .flatMap { version =>
          confirmDownload0(version.id, downloadType, token)
            .toRight(Redirect(ShowProject(author, slug)).withError("error.plugin.noConfirmDownload"))
        }
        .map { dl =>
          dl.downloadType match {
            case UploadedFile => Redirect(self.download(author, slug, target, Some(token)))
            case JarFile      => Redirect(self.downloadJar(author, slug, target, Some(token)))
            // Note: Shouldn't get here in the first place since sig files
            // don't need confirmation, but added as a failsafe.
            case SignatureFile => Redirect(self.downloadSignature(author, slug, target))
          }
        }
    }
  }

  /**
    * Confirms the download and prepares the unsafe download.
    */
  private def confirmDownload0(versionId: DbRef[Version], downloadType: Option[Int], token: String)(
      implicit requestHeader: Request[_],
      mdc: OreMDC
  ): OptionT[IO, UnsafeDownload] = {
    val addr = InetString(StatTracker.remoteAddress)
    val dlType = downloadType
      .flatMap(DownloadType.withValueOpt)
      .getOrElse(DownloadType.UploadedFile)
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
      .semiflatMap { warn =>
        val isInvalid = warn.hasExpired
        // warning has expired
        val remove = if (isInvalid) service.delete(warn).void else IO.unit

        remove.as((warn, isInvalid))
      }
      .filterNot(_._2)
      .map(_._1)
      .semiflatMap { warn =>
        // warning confirmed and redirect to download
        for {
          user <- users.current.value
          unsafeDownload <- service.insert(
            UnsafeDownload(userId = user.map(_.id.value), address = addr, downloadType = dlType)
          )
          _ <- service.update(warn)(_.copy(isConfirmed = true, downloadId = Some(unsafeDownload.id)))
        } yield unsafeDownload
      }
  }

  /**
    * Sends the specified project's current recommended version to the client.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return Sent file
    */
  def downloadRecommended(author: String, slug: String, token: Option[String]): Action[AnyContent] = {
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      request.project
        .recommendedVersion(ModelView.now(Version))
        .sequence
        .subflatMap(identity)
        .toRight(NotFound)
        .semiflatMap(sendVersion(request.project, _, token))
    }
  }

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
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      getVersion(request.project, versionString).semiflatMap(sendJar(request.project, _, token))
    }

  private def sendJar(
      project: Model[Project],
      version: Model[Version],
      token: Option[String],
      api: Boolean = false
  )(
      implicit request: ProjectRequest[_]
  ): IO[Result] = {
    if (project.visibility == Visibility.SoftDelete) {
      IO.pure(NotFound)
    } else {
      checkConfirmation(version, token).flatMap { passed =>
        if (!passed) {
          IO.pure(
            Redirect(
              self.showDownloadConfirm(
                project.ownerName,
                project.slug,
                version.name,
                Some(JarFile.value),
                api = Some(api)
              )
            )
          )
        } else {
          val fileName = version.fileName
          val path     = this.fileManager.getVersionDir(project.ownerName, project.name, version.name).resolve(fileName)
          project.owner.user.flatMap { projectOwner =>
            this.stats.versionDownloaded(version) {
              if (fileName.endsWith(".jar"))
                IO.pure(Ok.sendPath(path))
              else {
                val pluginFile = new PluginFile(path, signaturePath = null, projectOwner)
                val jarName    = fileName.substring(0, fileName.lastIndexOf('.')) + ".jar"
                val jarPath    = this.fileManager.env.tmp.resolve(project.ownerName).resolve(jarName)

                pluginFile.newJarStream
                  .use { jarIn =>
                    jarIn
                      .fold(
                        e => IO.raiseError(new Exception(e)),
                        is => IO(copy(is, jarPath, StandardCopyOption.REPLACE_EXISTING))
                      )
                      .void
                  }
                  .onError {
                    case e => IO(Logger.error("an error occurred while trying to send a plugin", e))
                  }
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
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      request.project
        .recommendedVersion(ModelView.now(Version))
        .sequence
        .subflatMap(identity)
        .toRight(NotFound)
        .semiflatMap(sendJar(request.project, _, token))
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
    ProjectAction(pluginId).asyncEitherT { implicit request =>
      val project = request.project
      getVersion(project, versionString).semiflatMap { version =>
        optToken
          .map { token =>
            confirmDownload0(version.id, Some(JarFile.value), token).value *>
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
    ProjectAction(pluginId).asyncEitherT { implicit request =>
      val data = request.data
      request.project
        .recommendedVersion(ModelView.now(Version))
        .sequence
        .subflatMap(identity)
        .toRight(NotFound)
        .semiflatMap(sendJar(data.project, _, token, api = true))
    }
  }

  /**
    * Sends the specified Project Version signature file to the client.
    *
    * @param author         Project owner
    * @param slug           Project slug
    * @param versionString  Version string
    * @return               Sent file
    */
  def downloadSignature(author: String, slug: String, versionString: String): Action[AnyContent] =
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      val project = request.project
      getVersion(project, versionString).map(sendSignatureFile(_, project))
    }

  /**
    * Downloads the signature file for the specified version.
    *
    * @param pluginId       Project unique plugin ID
    * @param versionString  Version name
    * @return               Sent file
    */
  def downloadSignatureById(pluginId: String, versionString: String): Action[AnyContent] =
    ProjectAction(pluginId).asyncEitherT { implicit request =>
      val project = request.project
      getVersion(project, versionString).map(sendSignatureFile(_, project))
    }

  /**
    * Downloads the signature file for the Project's recommended version.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Sent file
    */
  def downloadRecommendedSignature(author: String, slug: String): Action[AnyContent] =
    ProjectAction(author, slug).asyncEitherT { implicit request =>
      request.project
        .recommendedVersion(ModelView.now(Version))
        .sequence
        .subflatMap(identity)
        .toRight(NotFound)
        .map(sendSignatureFile(_, request.project))
    }

  /**
    * Downloads the signature file for the Project's recommended version.
    *
    * @param pluginId Project unique plugin ID
    * @return         Sent file
    */
  def downloadRecommendedSignatureById(pluginId: String): Action[AnyContent] = ProjectAction(pluginId).asyncEitherT {
    implicit request =>
      request.project
        .recommendedVersion(ModelView.now(Version))
        .sequence
        .subflatMap(identity)
        .toRight(NotFound)
        .map(sendSignatureFile(_, request.project))
  }

  private def sendSignatureFile(version: Version, project: Project)(implicit request: OreRequest[_]): Result = {
    if (project.visibility == Visibility.SoftDelete) {
      notFound
    } else {
      val path =
        this.fileManager.getVersionDir(project.ownerName, project.name, version.name).resolve(version.signatureFileName)
      if (notExists(path)) {
        Logger.warn("project version missing signature file")
        notFound
      } else Ok.sendPath(path)
    }
  }

}
