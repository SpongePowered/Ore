package controllers.apiv2

import java.util.UUID

import play.api.http.HttpErrorHandler
import play.api.inject.ApplicationLifecycle
import play.api.mvc.{Action, AnyContent, Result}

import controllers.OreControllerComponents
import controllers.apiv2.helpers.{APIScope, ApiError, ApiErrors}
import db.impl.query.apiv2.AuthQueries
import models.protocols.APIV2
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.ApiKeyTable
import ore.permission.{NamedPermission, Permission}

import cats.data.NonEmptyList
import cats.syntax.all._
import io.circe.Codec
import io.circe.derivation.annotations.SnakeCaseJsonCodec
import zio.interop.catz._
import zio.{IO, ZIO}

class Keys(
    val errorHandler: HttpErrorHandler,
    lifecycle: ApplicationLifecycle
)(
    implicit oreComponents: OreControllerComponents
) extends AbstractApiV2Controller(lifecycle) {
  import Keys._

  def createKey(): Action[KeyToCreate] =
    ApiAction(Permission.EditApiKeys, APIScope.GlobalScope)(parseCirce.decodeJson[KeyToCreate]).asyncF {
      implicit request =>
        val permsVal = NamedPermission.parseNamed(request.body.permissions).toValidNel("Invalid permission name")
        val nameVal = Some(request.body.name)
          .filter(_.nonEmpty)
          .toValidNel("Name was empty")
          .ensure(NonEmptyList.one("Name too long"))(_.length < 255)

        (permsVal, nameVal)
          .mapN { (perms, name) =>
            val perm     = Permission(perms.map(_.permission): _*)
            val isSubKey = request.apiInfo.key.forall(_.isSubKey(perm))

            if (!isSubKey) {
              IO.fail(BadRequest(ApiError("Not enough permissions to create that key")))
            } else {
              val tokenIdentifier = UUID.randomUUID().toString
              val token           = UUID.randomUUID().toString
              val ownerId         = request.user.get.id.value

              val nameTaken =
                TableQuery[ApiKeyTable].filter(t => t.name === name && t.ownerId === ownerId).exists.result

              val ifTaken = IO.fail(Conflict(ApiError("Name already taken")))
              val ifFree = service
                .runDbCon(AuthQueries.createApiKey(name, ownerId, tokenIdentifier, token, perm).run)
                .map(_ => Ok(CreatedApiKey(s"$tokenIdentifier.$token", perm.toNamedSeq)))

              (service.runDBIO(nameTaken): IO[Result, Boolean]).ifM(ifTaken, ifFree)
            }
          }
          .leftMap((ApiErrors.apply _).andThen(BadRequest.apply(_)).andThen(IO.fail(_)))
          .merge
    }

  def deleteKey(name: String): Action[AnyContent] =
    ApiAction(Permission.EditApiKeys, APIScope.GlobalScope).asyncF { implicit request =>
      for {
        user <- ZIO
          .fromOption(request.user)
          .orElseFail(BadRequest(ApiError("Public keys can't be used to delete")))
        rowsAffected <- service.runDbCon(AuthQueries.deleteApiKey(name, user.id.value).run)
      } yield if (rowsAffected == 0) NotFound else NoContent
    }
}
object Keys {

  implicit val namedPermissionCodec: Codec[NamedPermission] = APIV2.enumCodec(NamedPermission)(_.entryName)

  @SnakeCaseJsonCodec case class KeyToCreate(name: String, permissions: Seq[String])
  @SnakeCaseJsonCodec case class CreatedApiKey(key: String, perms: Seq[NamedPermission])
}
