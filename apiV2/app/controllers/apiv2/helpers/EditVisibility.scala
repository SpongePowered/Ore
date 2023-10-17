package controllers.apiv2.helpers

import play.api.http.Writeable
import play.api.mvc.Result

import models.protocols.APIV2.visibilityCodec
import ore.db.impl.common.Hideable
import ore.db.{DbRef, Model}
import ore.models.project.Visibility
import ore.models.user.User
import ore.permission.Permission
import ore.syntax._

import io.circe.Json
import io.circe.syntax._
import io.circe.derivation.annotations.SnakeCaseJsonCodec
import zio.{IO, UIO, ZIO}

@SnakeCaseJsonCodec case class EditVisibility(
    visibility: Visibility,
    comment: String
) {

  def process[A](
      toChange: Model[A],
      changer: DbRef[User],
      scopePerms: Permission,
      deletePerm: Permission,
      insertDiscourseUpdateJob: UIO[Unit],
      doHardDelete: Model[A] => UIO[Unit],
      createLog: (String, String) => UIO[Unit]
  )(implicit jsonWrite: Writeable[Json], hide: Hideable[UIO, A]): ZIO[Any, Result, Result] = {
    import play.api.mvc.Results._
    val forumVisbility =
      if (Visibility.isPublic(visibility) != Visibility.isPublic(toChange.hVisibility))
        insertDiscourseUpdateJob
      else IO.unit

    val nonReviewerChecks = visibility match {
      case Visibility.NeedsApproval =>
        val cond = toChange.hVisibility == Visibility.NeedsChanges &&
          scopePerms.has(Permission.EditProjectSettings)
        if (cond) ZIO.unit
        else ZIO.fail(Forbidden)
      case Visibility.SoftDelete =>
        if (scopePerms.has(deletePerm)) ZIO.unit else ZIO.fail(Forbidden)
      case v => ZIO.fail(BadRequest(Json.obj("error" := s"Project can't be changed to $v")))
    }

    val permChecks = if (scopePerms.has(Permission.Reviewer)) ZIO.unit else nonReviewerChecks

    val projectAction =
      if (toChange.hVisibility == Visibility.New) doHardDelete(toChange)
      else toChange.setVisibility(visibility, comment, changer)

    val log = createLog(visibility.nameKey, toChange.hVisibility.nameKey)

    permChecks *> (forumVisbility <&> projectAction) *> log.as(NoContent)
  }
}
