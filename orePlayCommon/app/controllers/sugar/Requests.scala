package controllers.sugar

import java.time.OffsetDateTime

import play.api.mvc.{Request, Results, WrappedRequest}

import models.viewhelper._
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.{Model, ModelService}
import ore.models.api.ApiKey
import ore.models.organization.Organization
import ore.models.project.{Project, Version}
import ore.models.user.User
import ore.permission.Permission
import ore.permission.scope.{GlobalScope, HasScope, OrganizationScope, ProjectScope, Scope}
import ore.util.OreMDC
import util.syntax._

import cats.Applicative
import org.slf4j.MDC
import zio.{UIO, ZIO}

/**
  * Contains the custom WrappedRequests used by Ore.
  */
object Requests {

  case class ApiAuthInfo(
      user: Option[Model[User]],
      key: Option[ApiKey],
      session: Option[String],
      expires: OffsetDateTime,
      globalPerms: Permission
  ) {

    def permissionIn[B: HasScope, F[_]](b: B)(implicit service: ModelService[F], F: Applicative[F]): F[Permission] =
      if (b.scope == GlobalScope) F.pure(globalPerms)
      else
        key
          .map(_.permissionsIn(b))
          .orElse(user.map(_.permissionsIn(b)))
          .getOrElse(F.pure(globalPerms))
  }

  case class ApiRequest[S <: Scope, A](apiInfo: ApiAuthInfo, scopePermission: Permission, scope: S, request: Request[A])
      extends WrappedRequest[A](request)
      with OreMDC {
    def user: Option[Model[User]] = apiInfo.user

    def globalPermissions: Permission = apiInfo.globalPerms

    def permissionIn[B: HasScope, F[_]](b: B)(implicit service: ModelService[F], F: Applicative[F]): F[Permission] =
      apiInfo.permissionIn(b)

    override def logMessage(s: String): String = {
      user.foreach(mdcPutUser)
      s
    }

    override def afterLog(): Unit = mdcClear()

    def project(implicit service: ModelService[UIO], ev: S =:= ProjectScope): ZIO[Any, Results.Status, Model[Project]] =
      ModelView.now(Project).get(ev(scope).id).toZIO.orElseFail(Results.NotFound)

    def version(
        versionString: String
    )(implicit service: ModelService[UIO], ev: S =:= ProjectScope): ZIO[Any, Results.Status, Model[Version]] =
      ModelView
        .now(Version)
        .find(v => v.projectId === ev(scope).id && v.versionString === versionString)
        .toZIO
        .orElseFail(Results.NotFound)

    def organization(
        implicit service: ModelService[UIO],
        ev: S =:= OrganizationScope
    ): ZIO[Any, Results.Status, Model[Organization]] =
      ModelView.now(Organization).get(ev(scope).id).toZIO.orElseFail(Results.NotFound)
  }

  private def mdcPutUser(user: Model[User]): Unit = {
    MDC.put("currentUserId", user.id.toString)
    MDC.put("currentUserName", user.name)
  }

  private def mdcPutProject(project: Model[Project]): Unit = {
    MDC.put("currentProjectId", project.id.toString)
    MDC.put("currentProjectSlug", project.slug)
  }

  private def mdcPutOrg(orga: Model[Organization]): Unit = {
    MDC.put("currentOrgaId", orga.id.toString)
    MDC.put("currentOrgaName", orga.name)
  }

  private def mdcClear(): Unit = {
    MDC.remove("currentUserId")
    MDC.remove("currentUserName")
    MDC.remove("currentProjectId")
    MDC.remove("currentProjectSlug")
    MDC.remove("currentOrgaId")
    MDC.remove("currentOrgaName")
  }

  /**
    * Base Request for Ore that holds all data needed for rendering the header
    */
  sealed trait OreRequest[A] extends WrappedRequest[A] with OreMDC {
    def headerData: HeaderData
    def currentUser: Option[Model[User]] = headerData.currentUser
    def hasUser: Boolean                 = headerData.currentUser.isDefined

    override def afterLog(): Unit = mdcClear()
  }

  final class SimpleOreRequest[A](val headerData: HeaderData, val request: Request[A])
      extends WrappedRequest[A](request)
      with OreRequest[A] {
    override def logMessage(s: String): String = {
      currentUser.foreach(mdcPutUser)
      s
    }
  }

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
      with UserScopedRequest[A] {
    override def logMessage(s: String): String = {
      mdcPutUser(user)
      s
    }
  }

  /**
    * A request that holds a [[Project]].
    *
    * @param data Project data to hold
    * @param request Request to wrap
    */
  sealed class ProjectRequest[A](
      val data: ProjectData,
      val headerData: HeaderData,
      val request: Request[A]
  ) extends WrappedRequest[A](request)
      with OreRequest[A] {

    def project: Model[Project] = data.project

    override def logMessage(s: String): String = {
      currentUser.foreach(mdcPutUser)
      mdcPutProject(project)
      s
    }
  }

  /**
    * A request that holds a Project and a [[AuthRequest]].
    *
    * @param data Project data to hold
    * @param request An [[AuthRequest]]
    */
  final case class AuthedProjectRequest[A](
      override val data: ProjectData,
      override val headerData: HeaderData,
      override val request: AuthRequest[A]
  ) extends ProjectRequest[A](data, headerData, request)
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
      with OreRequest[A] {
    override def logMessage(s: String): String = {
      currentUser.foreach(mdcPutUser)
      mdcPutOrg(data.orga)
      s
    }
  }

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
