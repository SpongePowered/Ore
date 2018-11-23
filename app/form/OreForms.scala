package form

import java.net.{MalformedURLException, URL}
import javax.inject.Inject

import scala.util.Try

import play.api.data.Forms._
import play.api.data.format.Formatter
import play.api.data.validation.{Constraint, Invalid, Valid, ValidationError}
import play.api.data.{FieldMapping, Form, FormError, Mapping}

import controllers.sugar.Requests.ProjectRequest
import db.impl.OrePostgresDriver.api._
import db.{DbRef, ModelService}
import form.organization.{OrganizationAvatarUpdate, OrganizationMembersUpdate, OrganizationRoleSetBuilder}
import form.project._
import models.api.ProjectApiKey
import models.project.Page._
import models.project.{Channel, Page}
import models.user.Organization
import models.user.role.ProjectUserRole
import ore.OreConfig
import ore.project.factory.ProjectFactory
import ore.rest.ProjectApiKeyType

import cats.data.OptionT
import cats.effect.IO

/**
  * Collection of forms used in this application.
  */
//noinspection ConvertibleToMethodValue
class OreForms @Inject()(implicit config: OreConfig, factory: ProjectFactory, service: ModelService) {

  val url: Mapping[String] = text.verifying("error.url.invalid", text => {
    if (text.isEmpty)
      true
    else {
      try {
        new URL(text)
        true
      } catch {
        case _: MalformedURLException =>
          false
      }
    }
  })

  /**
    * Submits a member to be removed from a Project.
    */
  lazy val ProjectMemberRemove = Form(single("username" -> nonEmptyText))

  /**
    * Submits changes to a [[models.project.Project]]'s
    * [[ProjectUserRole]]s.
    */
  lazy val ProjectMemberRoles = Form(
    mapping(
      "users" -> list(longNumber),
      "roles" -> list(text)
    )(ProjectRoleSetBuilder.apply)(ProjectRoleSetBuilder.unapply)
  )

  /**
    * Submits a flag on a project for further review.
    */
  lazy val ProjectFlag = Form(
    mapping("flag-reason" -> number, "comment" -> nonEmptyText)(FlagForm.apply)(FlagForm.unapply)
  )

  /**
    * This is a Constraint checker for the ownerId that will search the list allowedIds to see if the number is in it.
    * @param allowedIds number that are allowed as ownerId
    * @return Constraint
    */
  def ownerIdInList[A](allowedIds: Seq[DbRef[A]]): Constraint[Option[DbRef[A]]] =
    Constraint("constraints.check") { ownerId =>
      val errors =
        if (ownerId.isDefined && !allowedIds.contains(ownerId.get)) Seq(ValidationError("error.plugin"))
        else Nil
      if (errors.isEmpty) Valid
      else Invalid(errors)
    }

  /**
    * Submits settings changes for a Project.
    */
  def ProjectSave(organisationUserCanUploadTo: Seq[DbRef[Organization]]) =
    Form(
      mapping(
        "category"     -> text,
        "issues"       -> url,
        "source"       -> url,
        "license-name" -> text,
        "license-url"  -> url,
        "description"  -> text,
        "users"        -> list(longNumber),
        "roles"        -> list(text),
        "userUps"      -> list(text),
        "roleUps"      -> list(text),
        "update-icon"  -> boolean,
        "owner"        -> optional(longNumber).verifying(ownerIdInList(organisationUserCanUploadTo)),
        "forum-sync"   -> boolean
      )(ProjectSettingsForm.apply)(ProjectSettingsForm.unapply)
    )

  /**
    * Submits a name change for a project.
    */
  lazy val ProjectRename = Form(single("name" -> text))

  /**
    * Submits a post reply for a project discussion.
    */
  lazy val ProjectReply = Form(
    mapping(
      "content" -> text(minLength = minLength, maxLength = maxLength),
      "poster"  -> optional(nonEmptyText)
    )(DiscussionReplyForm.apply)(DiscussionReplyForm.unapply)
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
    * Submits an avatar update for an [[models.user.Organization]].
    */
  lazy val OrganizationUpdateAvatar = Form(
    mapping(
      "avatar-method" -> nonEmptyText,
      "avatar-url"    -> optional(url)
    )(OrganizationAvatarUpdate.apply)(OrganizationAvatarUpdate.unapply)
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
    * Submits a new Channel for a Project.
    */
  lazy val ChannelEdit = Form(
    mapping(
      "channel-input" -> text.verifying(
        "Invalid channel name.",
        config.isValidChannelName(_)
      ),
      "channel-color-input" -> text.verifying(
        "Invalid channel color.",
        c => Channel.Colors.exists(_.hex.equalsIgnoreCase(c))
      ),
      "non-reviewed" -> default(boolean, false)
    )(ChannelData.apply)(ChannelData.unapply)
  )

  /**
    * Submits changes on a documentation page.
    */
  lazy val PageEdit = Form(
    mapping(
      "parent-id" -> optional(longNumber),
      "name"      -> optional(text),
      "content" -> optional(
        text(
          maxLength = maxLengthPage
        )
      )
    )(PageSaveForm.apply)(PageSaveForm.unapply).verifying(
      "error.maxLength",
      pageSaveForm => {
        val isHome   = pageSaveForm.parentId.isEmpty && pageSaveForm.name.contains(homeName)
        val pageSize = pageSaveForm.content.getOrElse("").length
        if (isHome)
          pageSize <= maxLength
        else
          pageSize <= maxLengthPage
      }
    )
  )

  /**
    * Submits a tagline change for a User.
    */
  lazy val UserTagline = Form(single("tagline" -> text))

  /**
    * Submits a PGP public key update.
    */
  lazy val UserPgpPubKey = Form(
    mapping(
      "pgp-pub-key" -> text
    )(PGPPublicKeySubmission.apply)(PGPPublicKeySubmission.unapply).verifying("error.invalidKey", _.validateKey())
  )

  /**
    * Submits a new Version.
    */
  lazy val VersionCreate = Form(
    mapping(
      "unstable"      -> boolean,
      "recommended"   -> boolean,
      "channel-input" -> text.verifying("Invalid channel name.", config.isValidChannelName(_)),
      "channel-color-input" -> text
        .verifying("Invalid channel color.", c => Channel.Colors.exists(_.hex.equalsIgnoreCase(c))),
      "non-reviewed" -> default(boolean, false),
      "content"      -> optional(text),
      "forum-post"   -> boolean
    )(VersionData.apply)(VersionData.unapply)
  )

  /**
    * Submits a change to a Version's description.
    */
  lazy val VersionDescription = Form(single("content" -> text))

  val projectApiKeyType: FieldMapping[ProjectApiKeyType] = of[ProjectApiKeyType](new Formatter[ProjectApiKeyType] {
    def bind(key: String, data: Map[String, String]): Either[Seq[FormError], ProjectApiKeyType] =
      data
        .get(key)
        .flatMap(id => Try(id.toInt).toOption.map(ProjectApiKeyType.withValue))
        .toRight(Seq(FormError(key, "error.required", Nil)))
    def unbind(key: String, value: ProjectApiKeyType): Map[String, String] = Map(key -> value.value.toString)
  })

  lazy val ProjectApiKeyCreate = Form(single("key-type" -> projectApiKeyType))

  def required(key: String): Seq[FormError] = Seq(FormError(key, "error.required", Nil))

  def projectApiKey: FieldMapping[OptionT[IO, ProjectApiKey]] =
    of[OptionT[IO, ProjectApiKey]](new Formatter[OptionT[IO, ProjectApiKey]] {
      def bind(key: String, data: Map[String, String]): Either[Seq[FormError], OptionT[IO, ProjectApiKey]] =
        data
          .get(key)
          .flatMap(id => Try(id.toLong).toOption)
          .map(service.access[ProjectApiKey]().get(_))
          .toRight(required(key))

      def unbind(key: String, value: OptionT[IO, ProjectApiKey]): Map[String, String] =
        value.value.unsafeRunSync().map(_.id.value.toString).map(key -> _).toMap
    })

  def ProjectApiKeyRevoke = Form(single("id" -> projectApiKey))

  def channel(implicit request: ProjectRequest[_]): FieldMapping[OptionT[IO, Channel]] =
    of[OptionT[IO, Channel]](new Formatter[OptionT[IO, Channel]] {
      def bind(key: String, data: Map[String, String]): Either[Seq[FormError], OptionT[IO, Channel]] =
        data
          .get(key)
          .map(channelOptF(_))
          .toRight(Seq(FormError(key, "api.deploy.channelNotFound", Nil)))

      def unbind(key: String, value: OptionT[IO, Channel]): Map[String, String] =
        value.value.unsafeRunSync().map(key -> _.name.toLowerCase).toMap
    })

  def channelOptF(c: String)(implicit request: ProjectRequest[_]): OptionT[IO, Channel] =
    request.data.project.channels.find(_.name.toLowerCase === c.toLowerCase)

  def VersionDeploy(implicit request: ProjectRequest[_]) =
    Form(
      mapping(
        "apiKey"      -> nonEmptyText,
        "channel"     -> channel,
        "recommended" -> default(boolean, true),
        "forumPost"   -> default(boolean, request.data.settings.forumSync),
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

  lazy val SyncSso = Form(
    tuple(
      "sso"     -> nonEmptyText,
      "sig"     -> nonEmptyText,
      "api_key" -> nonEmptyText
    )
  )
}
