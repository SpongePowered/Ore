package controllers.apiv2

import play.api.http.HttpErrorHandler
import play.api.inject.ApplicationLifecycle
import play.api.mvc.{Action, AnyContent}

import controllers.OreControllerComponents
import controllers.apiv2.helpers.{APIScope, APIScopeType}
import models.protocols.APIV2
import ore.permission.{NamedPermission, Permission}

import io.circe._
import io.circe.derivation.annotations.SnakeCaseJsonCodec

class Permissions(
    val errorHandler: HttpErrorHandler,
    lifecycle: ApplicationLifecycle
)(
    implicit oreComponents: OreControllerComponents
) extends AbstractApiV2Controller(lifecycle) {
  import Permissions._

  def showPermissions(pluginId: Option[String], organizationName: Option[String]): Action[AnyContent] =
    ApiAction(Permission.None, APIScope.GlobalScope).asyncF { implicit request =>
      permissionsInApiScope(pluginId, organizationName).map {
        case (scope, perms) =>
          Ok(
            KeyPermissions(
              scope.tpe,
              perms.toNamedSeq.toList
            )
          )
      }
    }

  def has(
      checkPermissions: Seq[NamedPermission],
      pluginId: Option[String],
      organizationName: Option[String]
  )(
      check: (Seq[Permission], Permission) => Boolean
  ): Action[AnyContent] =
    ApiAction(Permission.None, APIScope.GlobalScope).asyncF { implicit request =>
      permissionsInApiScope(pluginId, organizationName).map {
        case (scope, perms) =>
          Ok(PermissionCheck(scope.tpe, check(checkPermissions.map(_.permission), perms)))
      }
    }

  def hasAll(
      permissions: Seq[NamedPermission],
      pluginId: Option[String],
      organizationName: Option[String]
  ): Action[AnyContent] =
    has(permissions, pluginId, organizationName)((seq, perm) => seq.forall(perm.has(_)))

  def hasAny(
      permissions: Seq[NamedPermission],
      pluginId: Option[String],
      organizationName: Option[String]
  ): Action[AnyContent] =
    has(permissions, pluginId, organizationName)((seq, perm) => seq.exists(perm.has(_)))
}
object Permissions {

  implicit val namedPermissionCodec: Codec[NamedPermission] = APIV2.enumCodec(NamedPermission)(_.entryName)

  @SnakeCaseJsonCodec case class KeyPermissions(
      `type`: APIScopeType,
      permissions: List[NamedPermission]
  )

  @SnakeCaseJsonCodec case class PermissionCheck(
      `type`: APIScopeType,
      result: Boolean
  )
}
