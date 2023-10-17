package controllers

import java.time.OffsetDateTime

import play.api.mvc.{Action, AnyContent}

import controllers.sugar.Requests.AuthRequest
import form.OreForms
import ore.data.user.notification.NotificationType
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{OrganizationRoleTable, OrganizationTable, ProjectTable, UserTable}
import ore.db.{DbRef, Model}
import ore.markdown.MarkdownRenderer
import ore.models.admin.{Message, Review}
import ore.models.project.{Project, ReviewState, Version}
import ore.models.user.{LoggedActionType, LoggedActionVersion, Notification, User}
import ore.permission.Permission
import ore.permission.role.Role
import util.UserActionLogger
import util.syntax._
import views.{html => views}

import cats.data.NonEmptyList
import cats.syntax.all._
import io.circe.Json
import slick.lifted.{Rep, TableQuery}
import zio.interop.catz._
import zio.{UIO, ZIO}

/**
  * Controller for handling Review related actions.
  */
final class Reviews(forms: OreForms)(
    implicit oreComponents: OreControllerComponents,
    renderer: MarkdownRenderer
) extends OreBaseController {

  def showReviews(author: String, slug: String, versionString: String): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).andThen(ProjectAction(author, slug)).asyncF {
      implicit request =>
        for {
          version <- getVersion(request.project, versionString)
          dbio = version
            .mostRecentReviews(ModelView.raw(Review))
            .joinLeft(TableQuery[UserTable])
            .on(_.userId === _.id)
            .map(t => t._1 -> t._2.map(_.name))
            .result
          rv <- service.runDBIO(dbio)
        } yield {
          val unfinished = rv.map(_._1).filter(_.endedAt.isEmpty).sorted(Review.ordering2).headOption
          Ok(views.users.admin.reviews(Model.unwrapNested(unfinished), rv, request.project, version))
        }
    }

  def createReview(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).asyncF { implicit request =>
      getProjectVersion(author, slug, versionString).flatMap { version =>
        val review = Review(
          version.id,
          request.user.id,
          None,
          Json.obj()
        )
        this.service.insert(review).as(Redirect(routes.Reviews.showReviews(author, slug, versionString)))
      }
    }
  }

  def reopenReview(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).asyncF { implicit request =>
      for {
        version <- getProjectVersion(author, slug, versionString)
        review  <- version.mostRecentReviews(ModelView.now(Review)).one.toZIOWithError(notFound)
        _ <- service.update(version)(
          _.copy(
            reviewState = ReviewState.Unreviewed,
            approvedAt = None,
            reviewerId = None
          )
        )
        newReview <- service.update(review)(_.copy(endedAt = None))
        _         <- newReview.addMessage(Message("Reopened the review", System.currentTimeMillis(), "start"))
      } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
    }
  }

  def stopReview(author: String, slug: String, versionString: String): Action[String] = {
    Authenticated
      .andThen(PermissionAction(Permission.Reviewer))
      .asyncF(parse.form(forms.ReviewDescription)) { implicit request =>
        for {
          version   <- getProjectVersion(author, slug, versionString)
          review    <- version.mostRecentUnfinishedReview(ModelView.now(Review)).toZIOWithError(notFound)
          newReview <- service.update(review)(_.copy(endedAt = Some(OffsetDateTime.now())))
          _         <- newReview.addMessage(Message(request.body.trim, System.currentTimeMillis(), "stop"))
        } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
      }
  }

  def approveReview(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).asyncF { implicit request =>
      for {
        project <- getProject(author, slug)
        version <- getVersion(project, versionString)
        review  <- version.mostRecentUnfinishedReview(ModelView.now(Review)).toZIOWithError(notFound)
        _ <- (
          service.update(review)(_.copy(endedAt = Some(OffsetDateTime.now()))),
          // send notification that review happened
          sendReviewNotification(project, version)
        ).parTupled
      } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
    }
  }

  private def queryNotificationUsers(
      projectId: Rep[DbRef[Project]],
      userId: Rep[Option[DbRef[User]]]
  ): Query[(Rep[DbRef[User]], Rep[Option[Role]]), (DbRef[User], Option[Role]), Seq] = {
    // Query Orga Members
    val q1 = for {
      project <- TableQuery[ProjectTable] if project.id === projectId
      org     <- TableQuery[OrganizationTable] if org.id === project.ownerId
      roles   <- TableQuery[OrganizationRoleTable] if roles.organizationId === org.id // TODO roletype lvl in database?
      users   <- TableQuery[UserTable] if users.id === roles.userId
    } yield (users.id, roles.roleType.?)

    // Query version author
    val q2 = for {
      user <- TableQuery[UserTable] if user.id === userId
    } yield (user.id, None: Rep[Option[Role]])

    q1 ++ q2 // Union
  }

  private lazy val notificationUsersQuery = Compiled(queryNotificationUsers _)

  private def sendReviewNotification(
      project: Model[Project],
      version: Version
  ): UIO[Unit] = {
    val usersF =
      service.runDBIO(notificationUsersQuery((project.id, version.authorId)).result).map { list =>
        list.collect {
          case (res, Some(role)) if role.permissions.has(Permission.EditVersion) => res
          case (res, None)                                                       => res
        }
      }

    usersF
      .map { users =>
        users.map { userId =>
          Notification(
            userId = userId,
            notificationType = NotificationType.VersionReviewed,
            messageArgs = NonEmptyList.of("notification.project.reviewed", project.slug, version.versionString)
          )
        }
      }
      .flatMap(service.bulkInsert(_).unit)
  }

  def takeoverReview(author: String, slug: String, versionString: String): Action[String] = {
    Authenticated
      .andThen(PermissionAction(Permission.Reviewer))
      .asyncF(parse.form(forms.ReviewDescription)) { implicit request =>
        for {
          version <- getProjectVersion(author, slug, versionString)
          _ <- {
            // Close old review
            val closeOldReview = version
              .mostRecentUnfinishedReview(ModelView.now(Review))
              .toZIO
              .flatMap { oldreview =>
                (
                  oldreview.addMessage(Message(request.body.trim, System.currentTimeMillis(), "takeover")),
                  service.update(oldreview)(_.copy(endedAt = Some(OffsetDateTime.now())))
                ).parTupled.unit
              }
              .either
              .map(_.merge)

            // Then make new one
            (
              closeOldReview,
              this.service.insert(
                Review(
                  version.id,
                  request.user.id,
                  None,
                  Json.obj()
                )
              )
            ).parTupled
          }
        } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
      }
  }

  def editReview(author: String, slug: String, versionString: String, reviewId: DbRef[Review]): Action[String] = {
    Authenticated
      .andThen(PermissionAction(Permission.Reviewer))
      .asyncF(parse.form(forms.ReviewDescription)) { implicit request =>
        for {
          version <- getProjectVersion(author, slug, versionString)
          review  <- version.reviewById(reviewId).toZIOWithError(notFound)
          _       <- review.addMessage(Message(request.body.trim))
        } yield Ok("Review" + review)
      }
  }

  def addMessage(author: String, slug: String, versionString: String): Action[String] = {
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).asyncF(parse.form(forms.ReviewDescription)) {
      implicit request =>
        for {
          version <- getProjectVersion(author, slug, versionString)
          recentReview <- version
            .mostRecentUnfinishedReview(ModelView.now(Review))
            .toZIOWithError(Ok("Review"))
          currentUser <- users.current.toZIOWithError(Ok("Review"))
          _ <- {
            if (recentReview.userId == currentUser.id.value) {
              recentReview.addMessage(Message(request.body.trim))
            } else UIO.succeed(0)
          }
        } yield Ok("Review")
    }
  }

  def backlogToggle(author: String, slug: String, versionString: String): Action[AnyContent] = {
    Authenticated.andThen(PermissionAction[AuthRequest](Permission.Reviewer)).asyncF { implicit request =>
      for {
        version <- getProjectVersion(author, slug, versionString)
        oldState <- ZIO.fromEither(
          Either.cond(
            Seq(ReviewState.Backlog, ReviewState.Unreviewed).contains(version.reviewState),
            version.reviewState,
            BadRequest("Invalid state for toggle backlog")
          )
        )
        newState = oldState match {
          case ReviewState.Unreviewed => ReviewState.Backlog
          case ReviewState.Backlog    => ReviewState.Unreviewed
          case _                      => oldState
        }
        _ <- UserActionLogger.log(
          request,
          LoggedActionType.VersionReviewStateChanged,
          version.id,
          newState.toString,
          oldState.toString
        )(LoggedActionVersion(_, Some(version.projectId)))
        _ <- service.update(version)(_.copy(reviewState = newState))
      } yield Redirect(routes.Reviews.showReviews(author, slug, versionString))
    }
  }
}
