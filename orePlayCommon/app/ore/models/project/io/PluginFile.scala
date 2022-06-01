package ore.models.project.io

import scala.language.higherKinds

import java.io._
import java.nio.file.{Files, Path}
import java.util.jar.{JarFile, JarInputStream}
import java.util.zip.{ZipEntry, ZipFile}

import scala.jdk.CollectionConverters._

import play.api.i18n.Messages

import ore.OreConfig
import ore.db.Model
import ore.models.project.PluginInfoParser
import ore.models.user.{User, UserOwned}

import cats.effect.{Resource, Sync}
import cats.syntax.all._

/**
  * Represents an uploaded plugin file.
  *
  * @param path Path to uploaded file
  */
class PluginFile(val path: Path, val pluginFileName: String, val user: Model[User]) {

  /**
    * Reads the temporary file's plugin meta file and returns the result.
    *
    * TODO: More validation on PluginMetadata results (null checks, etc)
    *
    * @return Plugin metadata or an error message
    */
  def loadMeta[F[_]](
      implicit messages: Messages,
      F: Sync[F],
      config: OreConfig
  ): F[Either[String, PluginFileWithData]] = {
    val res = newJarStream
      .flatMap { in =>
        val jarIn = F.delay(in.map(new JarInputStream(_)))
        Resource.make(jarIn) {
          case Right(is) => F.delay(is.close())
          case _         => F.unit
        }
      }
      .use { eJarIn =>
        F.delay {
          eJarIn.map { jarIn =>
            val isMixin = PluginInfoParser.isMixin(jarIn)
            //TODO: Use errors
            val (errors, entries) = PluginInfoParser.processJar(jarIn)
            val entriesWithMixin  = entries.map(_.copy(mixin = isMixin))

            if (entries.isEmpty)
              Left(messages("error.plugin.metaNotFound"))
            else
              Right(new PluginFileWithData(path, pluginFileName, user, entriesWithMixin))
          }
        }
      }

    res.map(_.flatMap(identity))
  }

  /**
    * Returns a new [[InputStream]] for this [[PluginFile]]'s main JAR file.
    *
    * @return InputStream of JAR
    */
  def newJarStream[F[_]](implicit F: Sync[F]): Resource[F, Either[String, InputStream]] = {
    if (this.pluginFileName.endsWith(".jar"))
      Resource
        .fromAutoCloseable[F, InputStream](F.delay(Files.newInputStream(this.path)))
        .flatMap(is => Resource.pure(Right(is)))
    else
      Resource
        .fromAutoCloseable(F.delay(new ZipFile(this.path.toFile)))
        .flatMap { zip =>
          val jarIn = F.delay(findTopLevelJar(zip).map(zip.getInputStream))

          Resource.make(jarIn) {
            case Right(is) => F.delay(is.close())
            case _         => F.unit
          }
        }
  }

  private def findTopLevelJar(zip: ZipFile): Either[String, ZipEntry] = {
    val pluginEntry = zip.entries().asScala.find { entry =>
      val name = entry.getName
      !entry.isDirectory && name.split("/").length == 1 && name.endsWith(".jar")
    }

    pluginEntry.toRight("error.plugin.jarNotFound")
  }
}
object PluginFile {
  implicit val isUserOwned: UserOwned[PluginFile] = (a: PluginFile) => a.user.id.value
}
