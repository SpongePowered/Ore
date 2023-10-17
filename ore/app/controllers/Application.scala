package controllers

import java.io.StringWriter
import java.net.{InetAddress, NetworkInterface}
import java.sql.Timestamp
import java.time.temporal.ChronoUnit
import java.time.{LocalDate, OffsetDateTime}
import java.util.Date
import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.util.Try

import play.api.http.{ContentTypes, HttpErrorHandler, Writeable}
import play.api.mvc.{Action, ActionBuilder, AnyContent}
import play.api.routing.JavaScriptReverseRouter

import controllers.sugar.CircePlayController
import controllers.sugar.Requests.AuthRequest
import db.impl.query.AppQueries
import form.OreForms
import models.querymodels.{FlagActivity, ReviewActivity}
import models.viewhelper.{OrganizationData, UserData}
import ore.db._
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema._
import ore.markdown.MarkdownRenderer
import ore.member.MembershipDossier
import ore.models.organization.Organization
import ore.models.project._
import ore.models.user._
import ore.models.user.role._
import ore.permission._
import ore.permission.role.{Role, RoleCategory}
import util.syntax._
import util.{Sitemap, UserActionLogger}
import views.{html => views}

import akka.util.{ByteString, Timeout}
import cats.Order
import cats.syntax.all._
import akka.actor.ActorSystem
import zio.interop.catz._
import zio.{IO, Task, UIO, ZIO}

/**
  * Main entry point for application.
  */
final class Application(forms: OreForms, val errorHandler: HttpErrorHandler)(
    implicit oreComponents: OreControllerComponents,
    renderer: MarkdownRenderer,
    actorSystem: ActorSystem
) extends OreBaseController {

  private def FlagAction = Authenticated.andThen(PermissionAction[AuthRequest](Permission.ModNotesAndFlags))

  def javascriptRoutes: Action[AnyContent] = Action { implicit request =>
    Ok(
      JavaScriptReverseRouter("jsRoutes")(
        controllers.project.routes.javascript.Projects.showFlags,
        controllers.project.routes.javascript.Projects.showNotes,
        controllers.project.routes.javascript.Projects.showStargazers,
        controllers.project.routes.javascript.Projects.toggleStarred,
        controllers.project.routes.javascript.Projects.showWatchers,
        controllers.project.routes.javascript.Projects.setWatching,
        controllers.project.routes.javascript.Projects.flag,
        controllers.project.routes.javascript.Versions.download,
        controllers.routes.javascript.Users.editApiKeys,
        controllers.routes.javascript.Users.logIn,
        controllers.routes.javascript.Users.signUp,
        controllers.routes.javascript.Users.logOut,
        controllers.routes.javascript.Users.showAuthors,
        controllers.routes.javascript.Users.showStaff,
        controllers.routes.javascript.Users.showNotifications,
        controllers.routes.javascript.Users.saveTagline,
        controllers.routes.javascript.Application.showLog,
        controllers.routes.javascript.Application.linkOut,
        controllers.routes.javascript.Application.showActivities,
        controllers.routes.javascript.Application.userAdmin,
        controllers.routes.javascript.Application.swagger,
        controllers.routes.javascript.Application.showFlags,
        controllers.routes.javascript.Application.showProjectVisibility,
        controllers.routes.javascript.Application.showQueue,
        controllers.routes.javascript.Application.showStats,
        controllers.routes.javascript.Application.showHealth,
        controllers.routes.javascript.Reviews.showReviews,
        controllers.routes.javascript.Organizations.showCreator
      )
    ).as("text/javascript")
  }

  /**
    * Show external link warning page.
    *
    * @return External link page
    */
  def linkOut(remoteUrl: String): Action[AnyContent] = OreAction(implicit request => Ok(views.linkout(remoteUrl)))

  /**
    * Display the home page.
    *
    * @return Home page
    */
  def showHome(): Action[AnyContent] = OreAction(implicit request => Ok(views.home()))

  /**
    * Shows the moderation queue for unreviewed versions.
    *
    * @return View of unreviewed versions.
    */
  def showQueue(): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).asyncF { implicit request =>
      // TODO: Pages
      service.runDbCon(AppQueries.getQueue.to[Vector]).map { queueEntries =>
        val (started, notStarted) = queueEntries.partitionEither(_.sort)
        Ok(views.users.admin.queue(started, notStarted))
      }
    }

  /**
    * Shows the overview page for flags.
    *
    * @return Flag overview
    */
  def showFlags(): Action[AnyContent] = FlagAction.asyncF { implicit request =>
    service
      .runDbCon(AppQueries.flags.to[Vector])
      .map(flagSeq => Ok(views.users.admin.flags(flagSeq)))
  }

  /**
    * Sets the resolved state of the specified flag.
    *
    * @param flagId   Flag to set
    * @param resolved Resolved state
    * @return         Ok
    */
  def setFlagResolved(flagId: DbRef[Flag], resolved: Boolean): Action[AnyContent] =
    FlagAction.asyncF { implicit request =>
      for {
        flag        <- ModelView.now(Flag).get(flagId).toZIOWithError(NotFound)
        user        <- users.current.value
        _           <- flag.markResolved(resolved, user)
        flagCreator <- flag.user[Task].orDie
        _ <- UserActionLogger.log(
          request,
          LoggedActionType.ProjectFlagResolved,
          flag.projectId,
          s"Flag Resolved by ${user.fold("unknown")(_.name)}",
          s"Flagged by ${flagCreator.name}"
        )(LoggedActionProject.apply)
      } yield Ok
    }

  def showHealth(): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](Permission.ViewHealth)).asyncF { implicit request =>
      implicit val timestampOrder: Order[Timestamp] = Order.from[Timestamp](_.compareTo(_))

      (
        service.runDbCon(AppQueries.getUnhealtyProjects(config.ore.projects.staleAge).to[Vector]),
        service.runDbCon(AppQueries.erroredJobs.to[Vector]),
        projects.missingFile.flatMap(versions => versions.toVector.traverse(v => v.project[Task].orDie.tupleLeft(v)))
      ).parMapN { (unhealtyProjects, erroredJobs, missingFileProjects) =>
        val noTopicProjects = unhealtyProjects.filter(p => p.topicId.isEmpty || p.postId.isEmpty)
        val staleProjects = unhealtyProjects
          .filter(_.lastUpdated > new Timestamp(new Date().getTime - config.ore.projects.staleAge.toMillis))
        val notPublic = unhealtyProjects.filter(_.visibility != Visibility.Public)
        Ok(
          views.users.admin.health(
            noTopicProjects,
            staleProjects,
            notPublic,
            Model.unwrapNested[Vector[(Version, Project)]](missingFileProjects),
            erroredJobs
          )
        )
      }
    }

  /**
    * Removes a trailing slash from a route.
    *
    * @param path Path with trailing slash
    * @return     Redirect to proper route
    */
  def removeTrail(path: String): Action[AnyContent] = Action(MovedPermanently(s"/$path"))

  def faviconRedirect(): Action[AnyContent] = Action(Redirect(assetsFinder.path("images/favicon.ico")))

  /**
    * Show the activities page for a user
    */
  def showActivities(user: String): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).asyncF { implicit request =>
      val dbProgram = for {
        reviewActibity <- AppQueries.getReviewActivity(user).to[Vector]
        flagActivity   <- AppQueries.getFlagActivity(user).to[Vector]
      } yield (reviewActibity, flagActivity)

      service.runDbCon(dbProgram).map {
        case (reviewActivity, flagActivity) =>
          val activities       = reviewActivity.map(_.asRight[FlagActivity]) ++ flagActivity.map(_.asLeft[ReviewActivity])
          val sortedActivities = activities.sortWith(sortActivities)
          Ok(views.users.admin.activity(user, sortedActivities))
      }
    }

  /**
    * Compares 2 activities (supply a [[Review]] or [[Flag]]) to decide which came first
    * @param o1 Review / Flag
    * @param o2 Review / Flag
    * @return Boolean
    */
  def sortActivities(
      o1: Either[FlagActivity, ReviewActivity],
      o2: Either[FlagActivity, ReviewActivity]
  ): Boolean = {
    val o1Time                                = o1.toOption.flatMap(_.endedAt).getOrElse(OffsetDateTime.MIN)
    val o2Time                                = o2.toOption.flatMap(_.endedAt).getOrElse(OffsetDateTime.MIN)
    implicit val order: Order[OffsetDateTime] = Order.fromComparable[OffsetDateTime]

    o1Time > o2Time
  }

  /**
    * Show stats
    * @return
    */
  def showStats(from: Option[String], to: Option[String]): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](Permission.ViewStats)).asyncF { implicit request =>
      def parseTime(time: Option[String], default: LocalDate) =
        time.map(s => Try(LocalDate.parse(s)).toOption).getOrElse(Some(default))

      val res = for {
        fromTime <- parseTime(from, LocalDate.now().minus(10, ChronoUnit.DAYS))
        toTime   <- parseTime(to, LocalDate.now())
        if fromTime.isBefore(toTime)
      } yield {
        service.runDbCon(AppQueries.getStats(fromTime, toTime).to[List]).map { stats =>
          Ok(views.users.admin.stats(stats, fromTime, toTime))
        }
      }

      res.getOrElse(IO.fail(BadRequest))
    }

  def showLog(
      oPage: Option[Int],
      userFilter: Option[String],
      projectFilter: Option[String],
      versionFilter: Option[String],
      pageFilter: Option[DbRef[Page]],
      actionFilter: Option[String],
      subjectFilter: Option[String]
  ): Action[AnyContent] = Authenticated.andThen(PermissionAction(Permission.ViewLogs)).asyncF { implicit request =>
    val pageSize = 50
    val page     = oPage.getOrElse(1)
    val offset   = (page - 1) * pageSize

    (
      service.runDbCon(
        AppQueries
          .getLog(oPage, userFilter, projectFilter, versionFilter, pageFilter, actionFilter, subjectFilter)
          .to[Vector]
      ),
      service.runDBIO(
        (
          TableQuery[LoggedActionProjectTable].size +
            TableQuery[LoggedActionVersionTable].size +
            TableQuery[LoggedActionPageTable].size +
            TableQuery[LoggedActionUserTable].size +
            TableQuery[LoggedActionOrganizationTable].size
        ).result
      )
    ).parMapN { (actions, size) =>
      Ok(
        views.users.admin.log(
          actions,
          pageSize,
          offset,
          page,
          size,
          userFilter,
          projectFilter,
          versionFilter,
          pageFilter,
          actionFilter,
          subjectFilter,
          request.headerData.globalPerm(Permission.ViewIp)
        )
      )
    }
  }

  def UserAdminAction: ActionBuilder[AuthRequest, AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.EditAdminSettings))

  def userAdmin(user: String): Action[AnyContent] = UserAdminAction.asyncF { implicit request =>
    for {
      u    <- users.withName(user).toZIOWithError(notFound)
      orga <- u.toMaybeOrganization(ModelView.now(Organization)).value
      projectRoles <- orga.fold(
        service.runDBIO(u.projectRoles(ModelView.raw(ProjectUserRole)).result)
      )(_ => IO.succeed(Nil))
      t2 <- (
        UserData.of(request, u),
        ZIO.foreachPar(projectRoles)(_.project[Task].orDie),
        OrganizationData.of[Task](orga).value.orDie
      ).parTupled
      (userData, projects, orgaData) = t2
    } yield {
      val pr = projects.zip(projectRoles)
      Ok(views.users.admin.userAdmin(userData, orgaData, pr.map(t => t._1.obj -> t._2)))
    }
  }

  def updateUser(userName: String): Action[(String, String, String)] =
    UserAdminAction.asyncF(parse.form(forms.UserAdminUpdate)) { implicit request =>
      users
        .withName(userName)
        .toZIOWithError(NotFound)
        .flatMap { user =>
          //TODO: Make the form take json directly
          val (thing, action, data) = request.body
          import play.api.libs.json._
          val json       = Json.parse(data)
          val orgDossier = MembershipDossier.organizationHasMemberships

          def updateRoleTable[M0 <: UserRoleModel[M0]: ModelQuery](model: ModelCompanion[M0])(
              modelAccess: ModelView.Now[UIO, model.T, Model[M0]],
              allowedCategory: RoleCategory,
              ownerType: Role,
              transferOwner: Model[M0] => UIO[Model[M0]]
          ): IO[Either[Status, Unit], Status] = {
            val id = (json \ "id").as[DbRef[M0]]
            action match {
              case "setRole" =>
                modelAccess.get(id).toZIO.mapError(Right.apply).flatMap { role =>
                  val roleType = Role.withValue((json \ "role").as[String])

                  if (roleType == ownerType)
                    transferOwner(role).as(Ok)
                  else if (roleType.category == allowedCategory && roleType.isAssignable)
                    service.update(role)(_.withRole(roleType)).as(Ok)
                  else
                    IO.fail(Left(BadRequest))
                }
              case "setAccepted" =>
                modelAccess
                  .get(id)
                  .toZIO
                  .mapError(Right.apply)
                  .flatMap(role => service.update(role)(_.withAccepted((json \ "accepted").as[Boolean])).as(Ok))
              case "deleteRole" =>
                modelAccess
                  .get(id)
                  .filter(_.role.isAssignable)
                  .toZIO
                  .mapError(Right.apply)
                  .flatMap(service.delete(_).as(Ok))
            }
          }

          def transferOrgOwner(r: Model[OrganizationUserRole]) =
            r.organization[Task]
              .orDie
              .flatMap(_.transferOwner(r.userId))
              .as(r)

          val res: IO[Either[Status, Unit], Status] = thing match {
            case "orgRole" =>
              val update = updateRoleTable(OrganizationUserRole)(
                user.organizationRoles(ModelView.now(OrganizationUserRole)),
                RoleCategory.Organization,
                Role.OrganizationOwner,
                transferOrgOwner
              )

              val isEmpty: IO[Either[Status, Unit], Boolean] = user
                .toMaybeOrganization(ModelView.now(Organization))
                .isEmpty

              isEmpty.ifM(update, IO.fail(Right(())))
            case "memberRole" =>
              user.toMaybeOrganization(ModelView.now(Organization)).toZIO.mapError(Right.apply).flatMap { orga =>
                updateRoleTable(OrganizationUserRole)(
                  orgDossier.memberships(orga),
                  RoleCategory.Organization,
                  Role.OrganizationOwner,
                  transferOrgOwner
                )
              }
            case "projectRole" =>
              val update = updateRoleTable(ProjectUserRole)(
                user.projectRoles(ModelView.now(ProjectUserRole)),
                RoleCategory.Project,
                Role.ProjectOwner,
                r => r.project[Task].orDie.flatMap(_.transferOwner[Task](r.userId).orDie).as(r)
              )

              val isEmpty: IO[Either[Status, Unit], Boolean] =
                user.toMaybeOrganization(ModelView.now(Organization)).isEmpty

              isEmpty.ifM(update, IO.fail(Right(())))
            case _ => IO.fail(Right(()))
          }

          res.mapError {
            case Right(_) => BadRequest
            case Left(e)  => e
          }
        }
    }

  def showProjectVisibility(): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](Permission.Reviewer)).asyncF { implicit request =>
      (
        service.runDbCon(AppQueries.getVisibilityNeedsApproval.to[Vector]),
        service.runDbCon(AppQueries.getVisibilityWaitingProject.to[Vector])
      ).mapN((needsApproval, waitingProject) => Ok(views.users.admin.visibility(needsApproval, waitingProject)))
    }

  def swagger(): Action[AnyContent] = OreAction(implicit request => Ok(views.swagger()))

  def sitemapIndex(): Action[AnyContent] = Action.asyncF { implicit request =>
    service.runDbCon(AppQueries.sitemapIndexUsers.to[Vector]).map { users =>
      def userSitemap(user: String) =
        <sitemap>
          <loc>{routes.Users.userSitemap(user).absoluteURL()}</loc>
        </sitemap>

      val sitemapIndex =
        <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          {users.map(userSitemap)}
          <sitemap>
            <loc>{routes.Application.globalSitemap.absoluteURL()}</loc>
          </sitemap>
        </sitemapindex>

      val writer = new StringWriter()
      xml.XML.write(writer, sitemapIndex, "UTF-8", xmlDecl = true, null)

      Ok(writer.toString).as("application/xml")
    }
  }

  val globalSitemap: Action[AnyContent] = Action { implicit requests =>
    Ok(
      Sitemap.asString(
        Sitemap.Entry(routes.Application.showHome(), changeFreq = Some(Sitemap.ChangeFreq.Hourly)),
        Sitemap.Entry(routes.Users.showAuthors(None, None), changeFreq = Some(Sitemap.ChangeFreq.Monthly)),
        Sitemap.Entry(routes.Application.swagger())
      )
    ).as("application/xml")
  }

  val robots: Action[AnyContent] = Action {
    Ok(
      s"""user-agent: *
         |Disallow: /login
         |Disallow: /signup
         |Disallow: /logout
         |Disallow: /linkout
         |Disallow: /admin
         |Disallow: /api
         |Disallow: /*/*/versions
         |Disallow: /*/*/watchers
         |Disallow: /*/*/stars
         |Disallow: /*/*/discuss
         |Disallow: /*/*/channels
         |Disallow: /*/*/manage
         |Disallow: /*/*/versionLog
         |Disallow: /*/*/flags
         |Disallow: /*/*/notes
         |Disallow: /*/*/channels
          
         |Allow: /*/*/versions/*
         |Allow: /api$$
          
         |Disallow: /*/*/versions/*/download
         |Disallow: /*/*/versions/*/recommended/download
         |Disallow: /*/*/versions/*/jar
         |Disallow: /*/*/versions/*/recommended/jar
         |Disallow: /*/*/versions/*/reviews
         |Disallow: /*/*/versions/*/new
         |Disallow: /*/*/versions/*/confirm

         |Sitemap: ${config.application.baseUrl}/sitemap.xml
         |""".stripMargin
    ).as("text/plain")
  }

  private def isLocalHost(address: String): Boolean = {
    val ipAddress = InetAddress.getByName(address)

    //https://stackoverflow.com/questions/2406341/how-to-check-if-an-ip-address-is-the-local-host-on-a-multi-homed-system/2406819
    ipAddress.isAnyLocalAddress || ipAddress.isLoopbackAddress ||
    Try(NetworkInterface.getByInetAddress(ipAddress) != null).getOrElse(false)
  }

  import _root_.io.circe.{Json, Encoder}
  import _root_.io.circe.syntax._

  implicit val jsonWriteable: Writeable[Json] = Writeable(js => ByteString(js.noSpaces), Some(ContentTypes.JSON))

  def actorTree(timeoutMs: Long): Action[AnyContent] = Action.async { request =>
    implicit val timeout: Timeout = Timeout(timeoutMs, TimeUnit.MILLISECONDS)

    import _root_.io.scalac.periscope.akka.tree.build

    if (isLocalHost(request.remoteAddress)) {
      build(actorSystem).map(tree => Ok(tree.asJson).as(ContentTypes.JSON))
    } else {
      Future.successful(Forbidden("Not localhost"))
    }
  }

  def actorCount(timeoutMs: Long): Action[AnyContent] = Action.async { request =>
    implicit val timeout: Timeout = Timeout(timeoutMs, TimeUnit.MILLISECONDS)

    import _root_.io.scalac.periscope.akka.counter.count

    if (isLocalHost(request.remoteAddress)) {
      count(actorSystem).map(res => Ok(Json.obj("result" := res)))
    } else {
      Future.successful(Forbidden("Not localhost"))
    }
  }
}
