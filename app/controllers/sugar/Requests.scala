package controllers.sugar

import java.sql.Timestamp

import play.api.mvc.{Request, WrappedRequest}

import db.{Model, ModelService}
import models.api.ApiKey
import models.project.Project
import models.user.{Organization, User}
import models.viewhelper._
import ore.permission.Permission
import ore.permission.scope.{GlobalScope, HasScope}
import util.syntax._

import cats.effect.IO

/**
  * Contains the custom WrappedRequests used by Ore.
  */
object Requests {

  case class ApiAuthInfo(user: Option[Model[User]], key: Option[ApiKey], expires: Timestamp, globalPerms: Permission)

  case class ApiRequest[A](apiInfo: ApiAuthInfo, request: Request[A]) extends WrappedRequest[A](request) {
    def user: Option[Model[User]] = apiInfo.user

    def globalPermissions: Permission = apiInfo.globalPerms

    def permissionIn[B: HasScope](b: B)(implicit service: ModelService): IO[Permission] =
      if (b.scope == GlobalScope) IO.pure(apiInfo.globalPerms)
      else apiInfo.key.fold(IO.pure(globalPermissions))(_.permissionsIn(b))
  }

  /**
    * Base Request for Ore that holds all data needed for rendering the header
    */
  sealed trait OreRequest[A] extends WrappedRequest[A] {
    def headerData: HeaderData
    def currentUser: Option[Model[User]] = headerData.currentUser
    def hasUser: Boolean                 = headerData.currentUser.isDefined
  }

  final class SimpleOreRequest[A](val headerData: HeaderData, val request: Request[A])
      extends WrappedRequest[A](request)
      with OreRequest[A]

  /** Represents a Request with a [[User]] and subject */
  sealed trait ScopedRequest[A] extends OreRequest[A] {
    type Subject
    def user: Model[User]
    def subject: Subject
  }
  object ScopedRequest {
    type Aux[A, Subject0] = ScopedRequest[A] { type Subject = Subject0 }
  }

  sealed trait UserScopedRequest[A] extends ScopedRequest[A] {
    type Subject = Model[User]
    def subject: Model[User] = user
  }
  object UserScopedRequest {
    implicit def hasScope: HasScope[UserScopedRequest[_]] = (_: UserScopedRequest[_]) => GlobalScope
  }

  /**
    * A request that hold the currently authenticated [[User]].
    *
    * @param user     Authenticated user
    * @param request  Request to wrap
    */
  final class AuthRequest[A](val user: Model[User], val headerData: HeaderData, request: Request[A])
      extends WrappedRequest[A](request)
      with OreRequest[A]
      with UserScopedRequest[A]

  /**
    * A request that holds a [[Project]].
    *
    * @param data Project data to hold
    * @param scoped scoped Project data to hold
    * @param request Request to wrap
    */
  sealed class ProjectRequest[A](
      val data: ProjectData,
      val scoped: ScopedProjectData,
      val headerData: HeaderData,
      val request: Request[A]
  ) extends WrappedRequest[A](request)
      with OreRequest[A] {

    def project: Model[Project] = data.project
  }

  /**
    * A request that holds a Project and a [[AuthRequest]].
    *
    * @param data Project data to hold
    * @param scoped scoped Project data to hold
    * @param request An [[AuthRequest]]
    */
  final case class AuthedProjectRequest[A](
      override val data: ProjectData,
      override val scoped: ScopedProjectData,
      override val headerData: HeaderData,
      override val request: AuthRequest[A]
  ) extends ProjectRequest[A](data, scoped, headerData, request)
      with ScopedRequest[A]
      with OreRequest[A] {

    type Subject = Model[Project]
    override def user: Model[User]       = request.user
    override val subject: Model[Project] = this.data.project
  }
  object AuthedProjectRequest {
    implicit def hasScope: HasScope[AuthedProjectRequest[_]] = HasScope.projectScope(_.subject.id.value)
  }

  /**
    * A request that holds an [[Organization]].
    *
    * @param data Organization data to hold
    * @param scoped scoped Organization data to hold
    * @param request      Request to wrap
    */
  sealed class OrganizationRequest[A](
      val data: OrganizationData,
      val scoped: ScopedOrganizationData,
      val headerData: HeaderData,
      val request: Request[A]
  ) extends WrappedRequest[A](request)
      with OreRequest[A]

  /**
    * A request that holds an [[Organization]] and an [[AuthRequest]].
    *
    * @param data Organization data to hold
    * @param scoped scoped Organization data to hold
    * @param request      Request to wrap
    */
  final case class AuthedOrganizationRequest[A](
      override val data: OrganizationData,
      override val scoped: ScopedOrganizationData,
      override val headerData: HeaderData,
      override val request: AuthRequest[A]
  ) extends OrganizationRequest[A](data, scoped, headerData, request)
      with ScopedRequest[A]
      with OreRequest[A] {
    type Subject = Model[Organization]
    override def user: Model[User]            = request.user
    override val subject: Model[Organization] = this.data.orga
  }
  object AuthedOrganizationRequest {
    implicit def hasScope: HasScope[AuthedOrganizationRequest[_]] = HasScope.orgScope(_.subject.id.value)
  }
}
