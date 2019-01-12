package models.statistic

import controllers.sugar.Requests.ProjectRequest
import db.impl.access.UserBase
import db.impl.schema.VersionDownloadsTable
import db.{DbRef, InsertFunc, ModelQuery, ObjId, ObjectTimestamp}
import models.project.Version
import models.user.User
import ore.StatTracker._
import security.spauth.SpongeAuthApi

import cats.effect.IO
import com.github.tminglei.slickpg.InetString
import slick.lifted.TableQuery

/**
  * Represents a unique download on a Project Version.
  *
  * @param id         Unique ID of entry
  * @param createdAt  Timestamp instant of creation
  * @param modelId    ID of model the stat is on
  * @param address    Client address
  * @param cookie     Browser cookie
  * @param userId     User ID
  */
case class VersionDownload(
    id: ObjId[VersionDownload],
    createdAt: ObjectTimestamp,
    modelId: DbRef[Version],
    address: InetString,
    cookie: String,
    userId: Option[DbRef[User]]
) extends StatEntry[Version] {

  override type M = VersionDownload
  override type T = VersionDownloadsTable
}

object VersionDownload {

  case class Partial(
      modelId: DbRef[Version],
      address: InetString,
      cookie: String,
      userId: Option[DbRef[User]] = None
  ) extends PartialStatEntry[Version, VersionDownload] {

    override def asFunc: InsertFunc[VersionDownload] =
      (id, time) => VersionDownload(id, time, modelId, address, cookie, userId)
  }

  implicit val query: ModelQuery[VersionDownload] =
    ModelQuery.from[VersionDownload](TableQuery[VersionDownloadsTable], _.copy(_, _))

  /**
    * Creates a new VersionDownload to be (or not be) recorded from an incoming
    * request.
    *
    * @param version  Version downloaded
    * @param request  Request to bind
    * @return         New VersionDownload
    */
  def bindFromRequest(version: Version)(
      implicit request: ProjectRequest[_],
      users: UserBase,
      auth: SpongeAuthApi
  ): IO[Partial] = {
    users.current.map(_.id.value).value.map { userId =>
      VersionDownload.Partial(
        modelId = version.id.value,
        address = InetString(remoteAddress),
        cookie = currentCookie,
        userId = userId
      )
    }

  }

}
