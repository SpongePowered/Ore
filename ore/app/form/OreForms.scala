package form

import scala.util.Try

import play.api.data.Forms._
import play.api.data.format.Formatter
import play.api.data.{FieldMapping, Form, FormError}

import controllers.sugar.Requests.ProjectRequest
import form.organization.{OrganizationMembersUpdate, OrganizationRoleSetBuilder}
import form.project._
import ore.OreConfig
import ore.db.access.ModelView
import ore.db.{Model, ModelService}
import ore.models.api.ProjectApiKey
import ore.models.project.Page
import util.syntax._

import cats.data.OptionT
import zio.UIO

/**
  * Collection of forms used in this application.
  */
//noinspection ConvertibleToMethodValue
class OreForms(
    implicit config: OreConfig,
    service: ModelService[UIO],
    runtime: zio.Runtime[Any]
) {

  /**
    * Submits a flag on a project for further review.
    */
  lazy val ProjectFlag = Form(
    mapping("flag-reason" -> number, "comment" -> nonEmptyText)(FlagForm.apply)(FlagForm.unapply)
  )

  /**
    * Submits a list of organization members to be invited.
    */
  lazy val OrganizationCreate = Form(
    mapping(
      "name"  -> nonEmptyText,
      "users" -> list(longNumber),
      "roles" -> list(text)
    )(OrganizationRoleSetBuilder.apply)(OrganizationRoleSetBuilder.unapply)
  )

  /**
    * Submits an organization member for removal.
    */
  lazy val OrganizationMemberRemove = Form(single("username" -> nonEmptyText))

  /**
    * Submits a list of members to be added or updated.
    */
  lazy val OrganizationUpdateMembers = Form(
    mapping(
      "users"   -> list(longNumber),
      "roles"   -> list(text),
      "userUps" -> list(text),
      "roleUps" -> list(text)
    )(OrganizationMembersUpdate.apply)(OrganizationMembersUpdate.unapply)
  )

  /**
    * Submits a tagline change for a User.
    */
  lazy val UserTagline = Form(single("tagline" -> text))

  def required(key: String): Seq[FormError] = Seq(FormError(key, "error.required", Nil))

  def projectApiKey: FieldMapping[OptionT[UIO, Model[ProjectApiKey]]] =
    of[OptionT[UIO, Model[ProjectApiKey]]](new Formatter[OptionT[UIO, Model[ProjectApiKey]]] {
      def bind(key: String, data: Map[String, String]): Either[Seq[FormError], OptionT[UIO, Model[ProjectApiKey]]] =
        data
          .get(key)
          .flatMap(id => Try(id.toLong).toOption)
          .map(ModelView.now(ProjectApiKey).get(_))
          .toRight(required(key))

      def unbind(key: String, value: OptionT[UIO, Model[ProjectApiKey]]): Map[String, String] =
        runtime.unsafeRun(value.value).map(_.id.toString).map(key -> _).toMap
    })

  def ProjectApiKeyRevoke = Form(single("id" -> projectApiKey))

  def VersionDeploy(implicit request: ProjectRequest[_]) =
    Form(
      mapping(
        "apiKey"      -> nonEmptyText,
        "channel"     -> optional(nonEmptyText),
        "recommended" -> default(boolean, true),
        "forumPost"   -> default(boolean, request.data.project.settings.forumSync),
        "changelog"   -> optional(text(minLength = Page.minLength, maxLength = Page.maxLength))
      )(VersionDeployForm.apply)(VersionDeployForm.unapply)
    )

  lazy val ReviewDescription = Form(single("content" -> text))

  lazy val UserAdminUpdate = Form(
    tuple(
      "thing"  -> text,
      "action" -> text,
      "data"   -> text
    )
  )

  lazy val NoteDescription = Form(single("content" -> text))

  lazy val NeedsChanges = Form(single("comment" -> text))

  case class SyncSsoForm(sso: String, sig: String)
  lazy val SyncSso = Form(
    mapping(
      "sso" -> nonEmptyText,
      "sig" -> nonEmptyText
    )(SyncSsoForm.apply)(SyncSsoForm.unapply)
  )
}
