package models.statistic

import java.sql.Timestamp

import com.github.tminglei.slickpg.InetString
import com.google.common.base.Preconditions._
import controllers.sugar.Requests.ProjectRequest
import db.impl.VersionDownloadsTable
import db.impl.access.UserBase
import models.project.Version
import ore.StatTracker._
import util.instances.future._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Represents a unique download on a Project Version.
  *
  * @param id         Unique ID of entry
  * @param createdAt  Timestamp instant of creation
  * @param modelId    ID of model the stat is on
  * @param address    Client address
  * @param cookie     Browser cookie
  * @param _userId     User ID
  */
case class VersionDownload(override val id: Option[Int] = None,
                           override val createdAt: Option[Timestamp] = None,
                           override val modelId: Int,
                           override val address: InetString,
                           override val cookie: String,
                           private var _userId: Option[Int] = None)
                           extends StatEntry[Version](id, createdAt, modelId, address, cookie, _userId) {

  override type M = VersionDownload
  override type T = VersionDownloadsTable

  override def copyWith(id: Option[Int], theTime: Option[Timestamp]): VersionDownload = this.copy(id = id, createdAt = theTime)

}

object VersionDownload {

  /**
    * Creates a new VersionDownload to be (or not be) recorded from an incoming
    * request.
    *
    * @param version  Version downloaded
    * @param request  Request to bind
    * @return         New VersionDownload
    */
  def bindFromRequest(version: Version)(implicit ec: ExecutionContext, request: ProjectRequest[_], users: UserBase): Future[VersionDownload] = {
    checkNotNull(version, "null version", "")
    checkArgument(version.isDefined, "undefined version", "")
    checkNotNull(request, "null request", "")
    checkNotNull(users, "null user base", "")
    users.current.subflatMap(_.id).value.map { userId =>
      val dl = VersionDownload(
        modelId = version.id.get,
        address = InetString(remoteAddress),
        cookie = currentCookie,
        _userId = userId
      )
      dl.userBase = users
      dl
    }

  }

}
