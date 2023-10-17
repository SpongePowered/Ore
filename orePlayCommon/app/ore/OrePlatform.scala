package ore

import ore.OreConfig.Ore.Platform
import ore.OreConfig.Ore.Platform.NoVersionPolicy
import ore.models.project.io.VersionedPlatform

import cats.data.Writer
import cats.syntax.all._

object OrePlatform {

  def coarseVersionOf(platform: Platform)(version: String): String = platform.coarseVersionRegex.fold(version) {
    extractor =>
      version match {
        case extractor(coarseStrVersion) => coarseStrVersion
        case _                           => version
      }
  }

  def produceVersionWarning(platform: Platform)(version: Option[String]): Writer[List[String], Unit] = {
    val inverseVersion = version.fold(Some(""): Option[String])(_ => None)
    platform.noVersionPolicy match {
      case NoVersionPolicy.NotAllowed =>
        Writer.tell(
          inverseVersion
            .as(s"A missing version for the platform ${platform.name} will not be accepted in the future")
            .toList
        )
      case NoVersionPolicy.Warning =>
        Writer.tell(
          inverseVersion.as(s"You are recommended to supply a version for the platform ${platform.name}").toList
        )
      case NoVersionPolicy.Allowed => Writer.tell(Nil)
    }
  }

  def createVersionedPlatforms(
      dependencyIds: Seq[String],
      dependencyVersions: Seq[Option[String]]
  )(implicit config: OreConfig): Writer[List[String], List[VersionedPlatform]] = {
    dependencyIds
      .zip(dependencyVersions)
      .flatMap {
        case (depId, depVersion) =>
          config.ore.platformsByDependencyId.get(depId).map { platform =>
            produceVersionWarning(platform)(depVersion)
              .as(VersionedPlatform(platform.name, depVersion, depVersion.map(coarseVersionOf(platform))))
          }
      }
      .toList
      .sequence
  }
}
