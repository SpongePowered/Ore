package controllers

import scala.language.higherKinds

import javax.inject.Inject

import scala.concurrent.ExecutionContext

import play.api.http.FileMimeTypes
import play.api.i18n.{Langs, MessagesApi}
import play.api.mvc.{ControllerComponents, DefaultActionBuilder, PlayBodyParsers}

import controllers.sugar.Bakery
import db.impl.access.{OrganizationBase, ProjectBase, UserBase}
import ore.OreConfig
import ore.auth.SSOApi
import ore.db.ModelService
import ore.models.project.io.ProjectFiles

import zio.{UIO, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock

trait OreControllerComponents extends ControllerComponents {
  def uioEffects: OreControllerEffects[UIO]
  def bakery: Bakery
  def config: OreConfig
  def projectFiles: ProjectFiles[ZIO[Blocking, Nothing, ?]]
  def zioRuntime: zio.Runtime[Blocking with Clock]
}

trait OreControllerEffects[F[_]] {
  def service: ModelService[F]
  def sso: SSOApi[F]
  def users: UserBase[F]
  def projects: ProjectBase[F]
  def organizations: OrganizationBase[F]
}

case class DefaultOreControllerComponents @Inject()(
    uioEffects: OreControllerEffects[UIO],
    bakery: Bakery,
    config: OreConfig,
    actionBuilder: DefaultActionBuilder,
    parsers: PlayBodyParsers,
    messagesApi: MessagesApi,
    langs: Langs,
    fileMimeTypes: FileMimeTypes,
    executionContext: ExecutionContext,
    projectFiles: ProjectFiles[ZIO[Blocking, Nothing, ?]],
    zioRuntime: zio.Runtime[Blocking with Clock]
) extends OreControllerComponents

case class DefaultOreControllerEffects[F[_]] @Inject()(
    service: ModelService[F],
    sso: SSOApi[F],
    users: UserBase[F],
    projects: ProjectBase[F],
    organizations: OrganizationBase[F]
) extends OreControllerEffects[F]
