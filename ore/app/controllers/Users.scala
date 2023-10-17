package controllers

import scala.annotation.unused

import play.api.i18n.MessagesApi
import play.api.mvc._

import db.impl.access.UserBase.UserOrdering
import db.impl.query.UserPagesQueries
import form.OreForms
import models.viewhelper.{OrganizationData, ScopedOrganizationData, UserData}
import ore.auth.URLWithNonce
import ore.data.Prompt
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.query.UserQueries
import ore.db.impl.schema._
import ore.db.{DbRef, Model}
import ore.models.user.notification.{InviteFilter, NotificationFilter}
import ore.models.user.{FakeUser, _}
import ore.permission.Permission
import ore.permission.role.Role
import util.syntax._
import util.{Sitemap, UserActionLogger}
import views.{html => views}

import cats.syntax.all._
import zio.interop.catz._
import zio.{IO, Task, UIO, ZIO}

/**
  * Controller for general user actions.
  */
class Users(
    fakeUser: FakeUser,
    forms: OreForms
)(
    implicit oreComponents: OreControllerComponents,
    messagesApi: MessagesApi
) extends OreBaseController {

  private val baseUrl = this.config.application.baseUrl

  /**
    * Redirect to auth page for SSO authentication.
    *
    * @return Logged in page
    */
  def signUp(): Action[AnyContent] = Action.asyncF {
    redirectToSso(sso.getSignupUrl(s"$baseUrl/login"))
  }

  /**
    * Redirect to auth page for SSO authentication and then back here again.
    *
    * @param sso  Incoming payload from auth
    * @param sig  Incoming signature from auth
    * @return     Logged in home
    */
  def logIn(sso: Option[String], sig: Option[String], returnPath: Option[String]): Action[AnyContent] =
    Action.asyncF { implicit request =>
      if (this.fakeUser.isEnabled) {
        // Log in as fake user (debug only)
        this.config.checkDebug()
        users
          .getOrCreate(
            this.fakeUser.username,
            this.fakeUser,
            ifInsert = fakeUser => fakeUser.globalRoles.addAssoc(Role.OreAdmin.toDbRole.id).unit
          )
          .flatMap(fakeUser => this.redirectBack(returnPath.getOrElse(request.path), fakeUser))
      } else if (sso.isEmpty || sig.isEmpty) {
        redirectToSso(this.sso.getLoginUrl(s"$baseUrl/login"))
          .map(_.flashing("url" -> returnPath.getOrElse(request.path)))
      } else {
        for {
          // Redirected from SpongeSSO, decode SSO payload and convert to Ore user
          sponge <- this.sso
            .authenticate(sso.get, sig.get)(isNonceValid)
            .get
            .orElseFail(Redirect(ShowHome).withError("error.loginFailed"))
          fromSponge = sponge.toUser
          // Complete authentication
          user <- users.getOrCreate(sponge.username, fromSponge, _ => IO.unit)
          _    <- user.globalRoles.deleteAllFromParent
          _ <- sponge.newGlobalRoles.fold(IO.unit) { roles =>
            ZIO.foreachPar_(roles.map(_.toDbRole.id))(user.globalRoles.addAssoc(_))
          }
          result <- this.redirectBack(request.flash.get("url").getOrElse("/"), user)
        } yield result
      }
    }

  /**
    * Redirects the user to the auth verification page to re-enter their
    * password and then perform some action.
    *
    * @param returnPath Verified action to perform
    * @return           Redirect to verification
    */
  def verify(returnPath: Option[String]): Action[AnyContent] = Authenticated.asyncF {
    redirectToSso(sso.getVerifyUrl(s"${this.baseUrl}${returnPath.getOrElse("/")}"))
  }

  private def redirectToSso(url: URLWithNonce): IO[Result, Result] = {
    val available: IO[Result, Boolean] = sso.isAvailable

    available.ifM(
      service.insert(SignOn(url.nonce)).as(Redirect(url.url)),
      IO.fail(Redirect(ShowHome).withError("error.noLogin"))
    )
  }

  private def redirectBack(url: String, user: Model[User]) =
    Redirect(this.baseUrl + url).authenticatedAs(user, this.config.ore.session.maxAge.toSeconds.toInt)

  /**
    * Clears the current session.
    *
    * @return Home page
    */
  def logOut(): Action[AnyContent] = Action {
    Redirect(config.auth.api.url + "/accounts/logout/")
      .clearingSession()
      .flashing("noRedirect" -> "true")
  }

  /**
    * Shows the User's [[ore.models.project.Project]]s page for the user with the
    * specified username.
    *
    * @param username   Username to lookup
    * @return           View of user projects page
    */
  def showProjects(username: String): Action[AnyContent] = OreAction(implicit request => Ok(views.home()))

  /**
    * Submits a change to the specified user's tagline.
    *
    * @param username   User to update
    * @return           View of user page
    */
  def saveTagline(username: String): Action[String] =
    UserEditAction(username).asyncF(parse.form(forms.UserTagline)) { implicit request =>
      val maxLen  = this.config.ore.users.maxTaglineLen
      val tagline = request.body

      for {
        user <- users.withName(username).toZIOWithError(NotFound)
        _ <- {
          if (tagline.length > maxLen)
            IO.fail(Redirect(ShowUser(user)).withError(request.messages.apply("error.tagline.tooLong", maxLen)))
          else ZIO.succeed(())
        }
        _ <- UserActionLogger.log(
          request,
          LoggedActionType.UserTaglineChanged,
          user.id,
          tagline,
          user.tagline.getOrElse("null")
        )(LoggedActionUser.apply)
        _ <- service.update(user)(_.copy(tagline = Some(tagline)))
      } yield Redirect(ShowUser(user))
    }

  /**
    * Shows a list of [[ore.models.user.User]]s that have created a
    * [[ore.models.project.Project]].
    */
  def showAuthors(sort: Option[String], page: Option[Int]): Action[AnyContent] = OreAction.asyncF { implicit request =>
    val ordering = sort.getOrElse(UserOrdering.Projects)
    val p        = page.getOrElse(1)

    service.runDbCon(UserPagesQueries.getAuthors(p, ordering).to[Vector]).map { u =>
      Ok(views.users.authors(u, ordering, p))
    }
  }

  /**
    * Shows a list of [[ore.models.user.User]]s that have Ore staff roles.
    */
  def showStaff(sort: Option[String], page: Option[Int]): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.IsStaff)).asyncF { implicit request =>
      val ordering = sort.getOrElse(UserOrdering.Role)
      val p        = page.getOrElse(1)

      service.runDbCon(UserPagesQueries.getStaff(p, ordering).to[Vector]).map { u =>
        Ok(views.users.staff(u, ordering, p))
      }
    }

  /**
    * Displays the current user's unread notifications.
    *
    * @return Unread notifications
    */
  def showNotifications(notificationFilter: Option[String], inviteFilter: Option[String]): Action[AnyContent] = {
    Authenticated.asyncF { implicit request =>
      val user = request.user

      // Get visible notifications
      val nFilter: NotificationFilter = notificationFilter
        .flatMap(str => NotificationFilter.values.find(_.name.equalsIgnoreCase(str)))
        .getOrElse(NotificationFilter.Unread)

      val iFilter: InviteFilter = inviteFilter
        .flatMap(str => InviteFilter.values.find(_.name.equalsIgnoreCase(str)))
        .getOrElse(InviteFilter.All)

      val notificationsF = service.runDBIO(
        nFilter(user.notifications(ModelView.raw(Notification)))
          .joinLeft(TableQuery[UserTable])
          .on(_.originId === _.id)
          .result
      )
      val invitesF =
        iFilter(user).flatMap(i => i.toVector.parTraverse(invite => invite.subject[Task].orDie.tupleLeft(invite)))

      (notificationsF, invitesF).parMapN { (notifications, invites) =>
        Ok(
          views.users.notifications(
            Model.unwrapNested[Seq[(Model[Notification], Option[User])]](notifications),
            invites.map(t => t._1 -> t._2.obj),
            nFilter,
            iFilter
          )
        )
      }
    }
  }

  /**
    * Marks a [[ore.models.user.User]]'s notification as read.
    *
    * @param id Notification ID
    * @return   Ok if marked as read, NotFound if notification does not exist
    */
  def markNotificationRead(id: DbRef[Notification]): Action[AnyContent] = Authenticated.asyncF { implicit request =>
    request.user
      .notifications(ModelView.now(Notification))
      .get(id)
      .semiflatMap(notification => service.update(notification)(_.copy(isRead = true)).as(Ok))
      .getOrElse(notFound)
  }

  /**
    * Marks a [[Prompt]] as read for the authenticated
    * [[ore.models.user.User]].
    *
    * @param id Prompt ID
    * @return   Ok if successful
    */
  def markPromptRead(id: Int): Action[AnyContent] = Authenticated.asyncF { implicit request =>
    Prompt.values.find(_.value == id) match {
      case None         => IO.fail(BadRequest)
      case Some(prompt) => request.user.markPromptAsRead(prompt).as(Ok)
    }
  }

  def editApiKeys(username: String): Action[AnyContent] =
    Authenticated.asyncF { implicit request =>
      if (request.user.name == username) {
        for {
          t1 <- (
            getOrga(username).option,
            UserData.of(request, request.user),
            service.runDBIO(TableQuery[ApiKeyTable].filter(_.ownerId === request.user.id.value).result)
          ).parTupled
          (orga, userData, keys) = t1
          t2 <- (
            OrganizationData.of[Task](orga).value.orDie,
            ScopedOrganizationData.of(request.currentUser, orga).value
          ).parTupled
          (orgaData, scopedOrgaData) = t2
          totalPerms <- (
            service.runDbCon(UserQueries.allPossibleProjectPermissions(request.user.id).unique),
            service.runDbCon(UserQueries.allPossibleOrgPermissions(request.user.id).unique)
          ).parMapN(_.add(_).add(userData.userPerm))
        } yield {

          Ok(
            views.users.apiKeys(
              userData,
              (orgaData, scopedOrgaData).tupled,
              Model.unwrapNested(keys),
              totalPerms.toNamedSeq
            )
          )
        }
      } else IO.fail(Forbidden)
    }

  import controllers.project.{routes => projectRoutes}

  def userSitemap(user: String): Action[AnyContent] = Action.asyncF { implicit request =>
    def use[A](@unused a: A): Unit = ()

    val projectsQuery = for {
      u <- TableQuery[UserTable]

      p <- TableQuery[ProjectTable] if u.id === p.ownerId
      _ = use(p)
      if u.name === user
    } yield p.slug

    val versionQuery = for {
      u  <- TableQuery[UserTable]
      p  <- TableQuery[ProjectTable] if u.id === p.ownerId
      pv <- TableQuery[VersionTable] if p.id === pv.projectId
      _ = use(pv)
      if u.name === user
    } yield (p.slug, pv.versionString)

    val pageQuery = for {
      u  <- TableQuery[UserTable]
      p  <- TableQuery[ProjectTable] if u.id === p.ownerId
      pp <- TableQuery[PageTable] if p.id === pp.projectId
      _ = use(pp)
      if u.name === user
    } yield (p.slug, pp.name)

    for {
      projectsFiber <- service.runDBIO(projectsQuery.result).fork
      versionsFiber <- service.runDBIO(versionQuery.result).fork
      pagesFiber    <- service.runDBIO(pageQuery.result).fork
      userExists    <- ModelView.now(User).exists(_.name === user)
      res <- {
        if (userExists) {
          val projectsF = projectsFiber.join
          val versionsF = versionsFiber.join
          val pagesF    = pagesFiber.join

          //IntelliJ is stupid
          projectsF <&> versionsF <&> pagesF: UIO[((Seq[String], Seq[(String, String)]), Seq[(String, String)])]
        } else {
          versionsFiber.interrupt &>
            projectsFiber.interrupt &>
            pagesFiber.interrupt &>
            ZIO.fail(NotFound): IO[Result, Nothing]
        }
      }
      ((projects, versions), pages) = res
    } yield {
      val projectEntries = for (project <- projects) yield Sitemap.Entry(projectRoutes.Projects.show(user, project, ""))

      val versionEntries =
        for ((project, version) <- versions)
          yield Sitemap.Entry(
            showVersion(user, project, version)
          )

      val pageEntries =
        for ((project, page) <- pages)
          yield Sitemap.Entry(
            showPage(user, project, page)
          )

      Ok(
        Sitemap.asString(
          projectEntries ++
            versionEntries ++
            pageEntries :+ Sitemap.Entry(
            routes.Users.showProjects(user),
            changeFreq = Some(Sitemap.ChangeFreq.Weekly)
          ): _*
        )
      ).as("application/xml")
    }
  }
}
