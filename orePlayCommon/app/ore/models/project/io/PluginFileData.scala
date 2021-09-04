package ore.models.project.io

import scala.language.higherKinds
import java.io.BufferedReader

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.control.NonFatal
import ore.data.project.Dependency
import ore.db.{DbRef, Model, ModelService}
import ore.models.project.{TagColor, Version, VersionTag}
import org.spongepowered.plugin.meta.McModInfo
import org.spongepowered.plugin.metadata.builtin.MetadataParser
import org.spongepowered.plugin.metadata.model.PluginDependency

/**
  * The metadata within a [[PluginFile]]
  *
  * @author phase
  * @param data the data within a [[PluginFile]]
  */
class PluginFileData(data: Seq[DataValue]) {

  private val dataValues: Seq[DataValue] = data
    .groupBy(_.key)
    .flatMap {
      case (key: String, values: Seq[DataValue]) =>
        // combine dependency lists that may come from different files
        if (values.lengthIs > 1) {
          import cats.syntax.all._

          val (otherValues, depSeq) = values.toVector.partitionEither {
            case DependencyDataValue(_, deps) => Right(deps)
            case other                        => Left(other)
          }

          otherValues :+ DependencyDataValue(key, depSeq.flatten)
        } else values
    }
    .toSeq

  def id: Option[String] =
    getString("id")

  def name: Option[String] =
    getString("name")

  def description: Option[String] =
    getString("description")

  def version: Option[String] =
    getString("version")

  def authors: Seq[String] =
    getStrings("authors").getOrElse(Seq())

  def dependencies: Seq[Dependency] =
    getDeps("dependencies").getOrElse(Seq())

  private def getString(key: String): Option[String] = get(key).collect {
    case StringDataValue(_, value) => value
  }

  private def getStrings(key: String): Option[Seq[String]] = get(key).collect {
    case StringListValue(_, value) => value
  }

  private def getDeps(key: String): Option[Seq[Dependency]] = get(key).collect {
    case DependencyDataValue(_, value) => value
  }

  private def get(key: String): Option[DataValue] = dataValues.find(_.key == key)

  def isValidPlugin: Boolean = dataValues.exists {
    case _: StringDataValue => true
    case _                  => false
  }

  def createTags[F[_]](versionId: DbRef[Version])(implicit service: ModelService[F]): F[Seq[Model[VersionTag]]] = {
    val buffer = new ArrayBuffer[VersionTag]

    if (containsMixins) {
      val mixinTag = VersionTag(versionId, "Mixin", None, TagColor.Mixin)
      buffer += mixinTag
    }

    service.bulkInsert(buffer.toSeq)
  }

  /**
    * A mod using Mixins will contain the "MixinConfigs" attribute in their MANIFEST
    *
    * @return
    */
  def containsMixins: Boolean =
    dataValues.exists {
      case p: StringDataValue => p.key == "MixinConfigs"
      case _                  => false
    }

}

object PluginFileData {
  val fileTypes: Seq[FileTypeHandler] = Seq(McModInfoHandler, ManifestHandler, ModTomlHandler, SpongeJsonHandler)

  def fileNames: Seq[String] = fileTypes.map(_.fileName).distinct

  def getData(fileName: String, stream: BufferedReader): Seq[DataValue] =
    fileTypes.filter(_.fileName == fileName).flatMap(_.getData(stream))

}

/**
  * A data element in a data file.
  */
sealed trait DataValue {
  def key: String
}

/**
  * A data element that is a String, such as the plugin id or version
  *
  * @param value the value extracted from the file
  */
case class StringDataValue(key: String, value: String) extends DataValue

/**
  * A data element that is a list of strings, such as an authors list
  *
  * @param value the value extracted from the file
  */
case class StringListValue(key: String, value: Seq[String]) extends DataValue

/**
  * A data element that is a list of [[Dependency]]
  *
  * @param value the value extracted from the file
  */
case class DependencyDataValue(key: String, value: Seq[Dependency]) extends DataValue

sealed abstract case class FileTypeHandler(fileName: String) {
  def getData(bufferedReader: BufferedReader): Seq[DataValue]
}

object McModInfoHandler extends FileTypeHandler("mcmod.info") {

  @SuppressWarnings(Array("scalafix:DisableSyntax.null"))
  override def getData(bufferedReader: BufferedReader): Seq[DataValue] = {
    val dataValues = new ArrayBuffer[DataValue]
    try {
      val info = McModInfo.DEFAULT.read(bufferedReader).asScala
      if (info.lengthCompare(1) < 0) Nil
      else {
        val metadata = info.head

        if (metadata.id != null)
          dataValues += StringDataValue("id", metadata.id)

        if (metadata.version != null)
          dataValues += StringDataValue("version", metadata.version)

        if (metadata.name != null)
          dataValues += StringDataValue("name", metadata.name)

        if (metadata.description != null)
          dataValues += StringDataValue("description", metadata.description)

        if (metadata.url != null)
          dataValues += StringDataValue("url", metadata.url)

        if (metadata.authors != null)
          dataValues += StringListValue("authors", metadata.authors.asScala.toSeq)

        if (metadata.dependencies != null) {
          val dependencies = metadata.dependencies.asScala.map(p => Dependency(p.id, Option(p.version()))).toSeq
          dataValues += DependencyDataValue("dependencies", dependencies)
        }

        dataValues.toSeq
      }
    } catch {
      case NonFatal(e) =>
        e.printStackTrace()
        Nil
    }
  }
}

object ManifestHandler extends FileTypeHandler("META-INF/MANIFEST.MF") {
  override def getData(bufferedReader: BufferedReader): Seq[DataValue] = {
    val dataValues = new ArrayBuffer[DataValue]

    val lines = LazyList.continually(bufferedReader.readLine()).takeWhile(_ != null) // scalafix:ok
    // Check for Mixins
    for (line <- lines if line.startsWith("MixinConfigs: ")) {
      val mixinConfigs = line.split(": ")(1)
      dataValues += StringDataValue("MixinConfigs", mixinConfigs)
    }

    dataValues.toSeq
  }
}

object ModTomlHandler extends FileTypeHandler("mod.toml") {
  override def getData(bufferedReader: BufferedReader): Seq[DataValue] =
    // TODO: Get format from Forge once it has been decided on
    Nil
}

object SpongeJsonHandler extends FileTypeHandler("META-INF/plugins.json") {

  override def getData(bufferedReader: BufferedReader): Seq[DataValue] = {
    val dvs = new ArrayBuffer[DataValue]
    try {
      val metadata    = MetadataParser.read(bufferedReader)
      val firstPlugin = metadata.metadata().asScala.head
      dvs += StringDataValue("id", firstPlugin.id)
      dvs += StringDataValue("version", firstPlugin.version.toString)
      firstPlugin.name.toScala.map(dvs += StringDataValue("name", _))
      firstPlugin.description.toScala.map(dvs += StringDataValue("description", _))
      dvs += StringListValue("authors", firstPlugin.contributors().asScala.map(_.name()).toSeq)
      dvs += DependencyDataValue("dependencies", readDependencies(firstPlugin.dependencies().asScala))
      dvs.toSeq
    } catch {
      case NonFatal(e) =>
        e.printStackTrace()
        Nil
    }
  }

  def readDependencies(in: Iterable[PluginDependency]): Seq[Dependency] = {
    in.map(dep =>
        Dependency(dep.id, if (dep.version.hasRestrictions) {
          Some(dep.version.toString)
        } else {
          None
        })
      )
      .toSeq
  }

}
