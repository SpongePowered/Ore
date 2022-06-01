package controllers.apiv2.helpers

import scala.collection.immutable

import models.protocols.APIV2
import ore.db.ModelService
import ore.db.impl.schema.{OrganizationTable, ProjectTable, UserTable}
import ore.permission.scope.Scope
import ore.permission.scope.{
  GlobalScope => RealGlobalScope,
  OrganizationScope => RealOrganizationScope,
  ProjectScope => RealProjectScope
}

import enumeratum.{Enum, EnumEntry}
import io.circe._
import ore.db.impl.OrePostgresDriver.api._
import zio.{IO, UIO}

sealed abstract class APIScope[+RealScope <: Scope](val tpe: APIScopeType) {

  //Proper GADT support please
  def toRealScope(implicit service: ModelService[UIO]): IO[Unit, RealScope] = (this: APIScope[Scope]) match {
    case APIScope.GlobalScope => UIO.succeed(RealGlobalScope.asInstanceOf[RealScope])
    case APIScope.ProjectScope(projectOwner, projectSlug) =>
      service
        .runDBIO(
          TableQuery[ProjectTable]
            .filter(p => p.ownerName === projectOwner && p.slug.toLowerCase === projectSlug.toLowerCase)
            .map(_.id)
            .result
            .headOption
        )
        .get
        .orElseFail(())
        .map(RealProjectScope(_).asInstanceOf[RealScope])
    case APIScope.OrganizationScope(organizationName) =>
      val q = for {
        u <- TableQuery[UserTable]
        if u.name === organizationName
        o <- TableQuery[OrganizationTable] if u.id === o.id
      } yield o.id

      service
        .runDBIO(q.result.headOption)
        .get
        .orElseFail(())
        .map(RealOrganizationScope(_).asInstanceOf[RealScope])
  }
}
object APIScope {
  case object GlobalScope extends APIScope[RealGlobalScope.type](APIScopeType.Global)
  case class ProjectScope(projectOwner: String, projectSlug: String)
      extends APIScope[RealProjectScope](APIScopeType.Project)
  case class OrganizationScope(organizationName: String)
      extends APIScope[RealOrganizationScope](APIScopeType.Organization)
}

sealed abstract class APIScopeType extends EnumEntry with EnumEntry.Snakecase
object APIScopeType extends Enum[APIScopeType] {
  case object Global       extends APIScopeType
  case object Project      extends APIScopeType
  case object Organization extends APIScopeType

  val values: immutable.IndexedSeq[APIScopeType] = findValues

  implicit val codec: Codec[APIScopeType] = APIV2.enumCodec(APIScopeType)(_.entryName)
}
