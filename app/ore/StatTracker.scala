package ore

import java.util.UUID

import controllers.sugar.Bakery
import controllers.sugar.Requests.{OreRequest, ProjectRequest}
import db.ModelService
import db.impl.access.{ProjectBase, UserBase}
import db.impl.schema.StatSchema
import javax.inject.Inject
import models.project.Version
import models.statistic.{ProjectView, VersionDownload}
import ore.StatTracker.COOKIE_NAME
import play.api.cache.AsyncCacheApi
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Helper class for handling tracking of statistics.
  */
trait StatTracker {

  implicit val users: UserBase
  implicit val projects: ProjectBase

  val bakery: Bakery
  val viewSchema: StatSchema[ProjectView]
  val downloadSchema: StatSchema[VersionDownload]

  /**
    * Signifies that a project has been viewed with the specified request and
    * actions should be taken to check whether a view should be added to the
    * Project's view count.
    *
    * @param request Request to view the project
    */
  def projectViewed(projectRequest: ProjectRequest[_])(f: ProjectRequest[_] => Result)(implicit cache: AsyncCacheApi, request: OreRequest[_],
      ec: ExecutionContext): Future[Result] = {
    ProjectView.bindFromRequest(projectRequest).map { statEntry =>
      this.viewSchema.record(statEntry).andThen {
        case recorded => if (recorded.get) {
          projectRequest.data.project.addView()
        }
      }
      f(projectRequest).withCookies(bakery.bake(COOKIE_NAME, statEntry.cookie, secure = true))
    }
  }

  /**
    * Signifies that a version has been downloaded with the specified request
    * and actions should be taken to check whether a view should be added to
    * the Version's (and Project's) download count.
    *
    * @param version Version to check downloads for
    * @param request Request to download the version
    */
  def versionDownloaded(version: Version)(f: ProjectRequest[_] => Result)(implicit cache: AsyncCacheApi,request: ProjectRequest[_],
      ec: ExecutionContext): Future[Result] = {
    VersionDownload.bindFromRequest(version).map { statEntry =>
      this.downloadSchema.record(statEntry).andThen {
        case recorded => if (recorded.get) {
          version.addDownload()
          request.data.project.addDownload()
        }
      }
      f(request).withCookies(bakery.bake(COOKIE_NAME, statEntry.cookie, secure = true))
    }
  }

}

object StatTracker {

  val COOKIE_NAME = "_stat"

  /**
    * Gets or creates a unique ID for tracking statistics based on the browser.
    *
    * @param request  Request with cookie
    * @return         New or existing cookie
    */
  def currentCookie(implicit request: RequestHeader): String
  = request.cookies.get(COOKIE_NAME).map(_.value).getOrElse(UUID.randomUUID.toString)

  /**
    * Returns either the original client address from a X-Forwarded-For header
    * or the remoteAddress from the request if the header is not found.
    *
    * @param request  Request to get address of
    * @return         Remote address
    */
  def remoteAddress(implicit request: RequestHeader): String = {
    request.headers.get("X-Forwarded-For") match {
      case None => request.remoteAddress
      case Some(header) => header.split(',').headOption.map(_.trim).getOrElse(request.remoteAddress)
    }
  }

}

class OreStatTracker @Inject()(service: ModelService, override val bakery: Bakery) extends StatTracker {
  override val users         : UserBase                    = this.service.getModelBase(classOf[UserBase])
  override val projects      : ProjectBase                 = this.service.getModelBase(classOf[ProjectBase])
  override val viewSchema    : StatSchema[ProjectView]     = this.service.getSchemaByModel(classOf[ProjectView]).asInstanceOf[StatSchema[ProjectView]]

  override val downloadSchema: StatSchema[VersionDownload] = this.service.getSchemaByModel(classOf[VersionDownload])
    .asInstanceOf[StatSchema[VersionDownload]]
}
