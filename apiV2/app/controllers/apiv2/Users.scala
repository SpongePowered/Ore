package controllers.apiv2

import play.api.http.HttpErrorHandler
import play.api.inject.ApplicationLifecycle
import play.api.mvc.{Action, AnyContent, Result}

import controllers.OreControllerComponents
import controllers.apiv2.Users.{PaginatedCompactProjectResult, PaginatedUserResult, UserSortingStrategy}
import controllers.apiv2.helpers.{APIScope, ApiError, Pagination}
import db.impl.query.apiv2.{APIV2Queries, ActionsAndStatsQueries, UserQueries}
import models.protocols.APIV2
import models.viewhelper.HeaderData
import ore.db.DbRef
import ore.models.project.ProjectSortingStrategy
import ore.models.user.User
import ore.permission.Permission
import ore.permission.role.Role

import cats.syntax.all._
import enumeratum.values.{StringEnum, StringEnumEntry}
import io.circe._
import io.circe.derivation.annotations.SnakeCaseJsonCodec
import io.circe.syntax._
import zio.ZIO
import zio.interop.catz._

class Users(
    val errorHandler: HttpErrorHandler,
    lifecycle: ApplicationLifecycle
)(
    implicit oreComponents: OreControllerComponents
) extends AbstractApiV2Controller(lifecycle) {

  def listUsers(
      q: Option[String],
      minProjects: Option[Long],
      roles: Seq[Role],
      excludeOrganizations: Boolean,
      sort: Option[UserSortingStrategy],
      sortDescending: Boolean,
      limit: Option[Long],
      offset: Long
  ): Action[AnyContent] = ApiAction(Permission.ViewPublicInfo, APIScope.GlobalScope).asyncF {
    val realLimit  = limitOrDefault(limit, config.ore.users.authorPageSize)
    val realOffset = offsetOrZero(offset)

    val getUsers = service.runDbCon(
      UserQueries
        .userSearchQuery(
          q,
          minProjects.getOrElse(0L),
          roles,
          excludeOrganizations,
          sort.getOrElse(UserSortingStrategy.Name),
          sortDescending,
          realLimit,
          realOffset
        )
        .to[Vector]
    )

    val userCount = service.runDbCon(
      UserQueries
        .userSearchCountQuery(
          q,
          minProjects.getOrElse(0L),
          roles,
          excludeOrganizations
        )
        .unique
    )

    (getUsers <&> userCount).map {
      case (users, userCount) =>
        Ok(
          PaginatedUserResult(
            Pagination(realLimit, realOffset, userCount),
            users
          )
        )
    }
  }

  def showCurrentUser: Action[AnyContent] = ApiAction(Permission.ViewPublicInfo, APIScope.GlobalScope).asyncF { r =>
    r.user match {
      case Some(user) => service.runDbCon(UserQueries.userQuery(user.name).unique).map(a => Ok(a.asJson))
      case None       => ZIO.fail(Unauthorized(ApiError("Only user sessions for this endpoint")))
    }
  }

  def showUser(user: String): Action[AnyContent] =
    CachingApiAction(Permission.ViewPublicInfo, APIScope.GlobalScope).asyncF {
      service.runDbCon(UserQueries.userQuery(user).option).map(_.fold(NotFound: Result)(a => Ok(a.asJson)))
    }

  def showStarred(
      user: String,
      sort: Option[ProjectSortingStrategy],
      limit: Option[Long],
      offset: Long
  ): Action[AnyContent] =
    showUserAction(
      user,
      sort,
      limit,
      offset,
      ActionsAndStatsQueries.starredQuery,
      ActionsAndStatsQueries.starredCountQuery
    )

  def showWatching(
      user: String,
      sort: Option[ProjectSortingStrategy],
      limit: Option[Long],
      offset: Long
  ): Action[AnyContent] =
    showUserAction(
      user,
      sort,
      limit,
      offset,
      ActionsAndStatsQueries.watchingQuery,
      ActionsAndStatsQueries.watchingCountQuery
    )

  def showUserAction(
      user: String,
      sort: Option[ProjectSortingStrategy],
      limit: Option[Long],
      offset: Long,
      query: (
          String,
          Boolean,
          Option[DbRef[User]],
          ProjectSortingStrategy,
          Long,
          Long
      ) => doobie.Query0[Either[DecodingFailure, APIV2.CompactProject]],
      countQuery: (String, Boolean, Option[DbRef[User]]) => doobie.Query0[Long]
  ): Action[AnyContent] = CachingApiAction(Permission.ViewPublicInfo, APIScope.GlobalScope).asyncF { implicit request =>
    val realLimit = limitOrDefault(limit, config.ore.projects.initLoad)

    val getProjects = query(
      user,
      request.globalPermissions.has(Permission.SeeHidden),
      request.user.map(_.id),
      sort.getOrElse(ProjectSortingStrategy.Default),
      realLimit,
      offset
    ).to[Vector]

    val countProjects = countQuery(
      user,
      request.globalPermissions.has(Permission.SeeHidden),
      request.user.map(_.id)
    ).unique

    (service.runDbCon(getProjects).flatMap(ZIO.foreach(_)(ZIO.fromEither(_))).orDie, service.runDbCon(countProjects))
      .parMapN { (projects, count) =>
        Ok(
          PaginatedCompactProjectResult(
            Pagination(realLimit, offset, count),
            projects
          )
        )
      }
  }

  def getMemberships(user: String): Action[AnyContent] =
    CachingApiAction(Permission.ViewPublicInfo, APIScope.GlobalScope).asyncF {
      service.runDbCon(UserQueries.getMemberships(user).to[Vector]).map(r => Ok(r.asJson))
    }

  def showHeaderData(): Action[AnyContent] = ApiAction(Permission.ViewPublicInfo, APIScope.GlobalScope).asyncF { r =>
    HeaderData.of(r).map { headerData =>
      Ok(
        Json.obj(
          "hasNotice" := headerData.hasNotice,
          "hasUnreadNotifications" := headerData.hasUnreadNotifications,
          "unresolvedFlags" := headerData.unresolvedFlags,
          "hasProjectApprovals" := headerData.hasProjectApprovals,
          "hasReviewQueue" := headerData.hasReviewQueue,
          "readPrompts" := r.user.get.readPrompts.map(_.value)
        )
      )
    }
  }
}
object Users {
  @SnakeCaseJsonCodec case class PaginatedUserResult(
      pagination: Pagination,
      result: Seq[APIV2.User]
  )

  @SnakeCaseJsonCodec case class PaginatedCompactProjectResult(
      pagination: Pagination,
      result: Seq[APIV2.CompactProject]
  )

  sealed abstract class UserSortingStrategy(val value: String) extends StringEnumEntry
  object UserSortingStrategy extends StringEnum[UserSortingStrategy] {
    override def values: IndexedSeq[UserSortingStrategy] = findValues

    case object Name     extends UserSortingStrategy("name")
    case object Joined   extends UserSortingStrategy("join_date")
    case object Projects extends UserSortingStrategy("project_count")
  }
}
