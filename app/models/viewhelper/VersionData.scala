package models.viewhelper

import controllers.sugar.Requests.ProjectRequest
import db.ModelService
import models.project.{Channel, Project, Version}
import ore.Platform
import ore.project.Dependency

import cats.effect.{ContextShift, IO}
import cats.syntax.all._

case class VersionData(
    p: ProjectData,
    v: Version,
    c: Channel,
    approvedBy: Option[String], // Reviewer if present
    dependencies: Seq[(Dependency, Option[Project])]
) {

  def isRecommended: Boolean = p.project.recommendedVersionId.contains(v.id.value)

  def fullSlug = s"""${p.fullSlug}/versions/${v.versionString}"""

  /**
    * Filters out platforms from the dependencies
    * @return filtered dependencies
    */
  def filteredDependencies: Seq[(Dependency, Option[Project])] = {
    val platformIds = Platform.values.map(_.dependencyId)
    dependencies.filterNot(d => platformIds.contains(d._1.pluginId))
  }
}

object VersionData {
  def of[A](request: ProjectRequest[A], version: Version)(
      implicit service: ModelService,
      cs: ContextShift[IO]
  ): IO[VersionData] = {
    import cats.instances.list._
    val depsF = version.dependencies.parTraverse(dep => dep.project.value.tupleLeft(dep))

    (version.channel, version.reviewer.map(_.name).value, depsF).parMapN {
      case (channel, approvedBy, deps) =>
        VersionData(request.data, version, channel, approvedBy, deps)
    }
  }
}
