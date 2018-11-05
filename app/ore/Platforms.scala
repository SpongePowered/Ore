package ore

import scala.collection.immutable
import scala.concurrent.Future

import db.{ModelService, ObjectId, ObjectReference}
import models.project.{TagColor, VersionTag}
import ore.project.Dependency

import enumeratum.values._

/**
  * The Platform a plugin/mod runs on
  *
  * @author phase
  */
sealed abstract class Platform(
    val value: Int,
    val name: String,
    val platformCategory: PlatformCategory,
    val priority: Int,
    val dependencyId: String,
    val tagColor: TagColor,
    val url: String
) extends IntEnumEntry {

  def createGhostTag(versionId: ObjectReference, version: String): VersionTag =
    VersionTag(ObjectId.Uninitialized, versionId, name, version, tagColor)

}
object Platform extends IntEnum[Platform] {

  val values: immutable.IndexedSeq[ore.Platform] = findValues

  case object Sponge
      extends Platform(
        0,
        "Sponge",
        SpongeCategory,
        0,
        "spongeapi",
        TagColor.Sponge,
        "https://spongepowered.org/downloads"
      )

  case object SpongeForge
      extends Platform(
        2,
        "SpongeForge",
        SpongeCategory,
        2,
        "spongeforge",
        TagColor.SpongeForge,
        "https://www.spongepowered.org/downloads/spongeforge"
      )

  case object SpongeVanilla
      extends Platform(
        3,
        "SpongeVanilla",
        SpongeCategory,
        2,
        "spongevanilla",
        TagColor.SpongeVanilla,
        "https://www.spongepowered.org/downloads/spongevanilla"
      )

  case object SpongeCommon
      extends Platform(
        4,
        "SpongeCommon",
        SpongeCategory,
        1,
        "sponge",
        TagColor.SpongeCommon,
        "https://www.spongepowered.org/downloads"
      )

  case object Lantern
      extends Platform(5, "Lantern", SpongeCategory, 2, "lantern", TagColor.Lantern, "https://www.lanternpowered.org/")

  case object Forge
      extends Platform(1, "Forge", ForgeCategory, 0, "forge", TagColor.Forge, "https://files.minecraftforge.net/")

  def getPlatforms(dependencyIds: Seq[String]): Seq[Platform] = {
    Platform.values
      .filter(p => dependencyIds.contains(p.dependencyId))
      .groupBy(_.platformCategory)
      .flatMap(_._2.groupBy(_.priority).maxBy(_._1)._2)
      .toSeq
  }

  def ghostTags(versionId: ObjectReference, dependencies: Seq[Dependency]): Seq[VersionTag] = {
    Platform.values
      .filter(p => dependencies.map(_.pluginId).contains(p.dependencyId))
      .groupBy(_.platformCategory)
      .flatMap(_._2.groupBy(_.priority).maxBy(_._1)._2)
      .map(p => p.createGhostTag(versionId, dependencies.find(_.pluginId == p.dependencyId).get.version))
      .toSeq
  }

  def createPlatformTags(versionId: ObjectReference, dependencies: Seq[Dependency])(
      implicit service: ModelService
  ): Future[Seq[VersionTag]] = service.bulkInsert(ghostTags(versionId, dependencies))

}

/**
  * The category of a platform.
  * Examples would be
  *
  * Sponge <- SpongeAPI, SpongeForge, SpongeVanilla
  * Forge <- Forge (maybe Rift if that doesn't die?)
  * Bukkit <- Bukkit, Spigot, Paper
  * Canary <- Canary, Neptune
  *
  * @author phase
  */
sealed trait PlatformCategory {
  def name: String

  def getPlatforms: Seq[Platform] = Platform.values.filter(_.platformCategory == this)
}

case object SpongeCategory extends PlatformCategory {
  val name = "Sponge Plugins"
}

case object ForgeCategory extends PlatformCategory {
  val name = "Forge Mods"
}

object PlatformCategory {
  def getPlatformCategories: Seq[PlatformCategory] = Seq(SpongeCategory, ForgeCategory)
}
