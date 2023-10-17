package ore.models.project.io

import scala.language.higherKinds

import java.nio.file.Path

import ore.OreEnv
import ore.models.project.Project
import util.FileIO

import cats.effect.Bracket
import cats.syntax.all._
import cats.tagless.autoFunctorK

/**
  * Handles file management of Projects.
  */
@autoFunctorK
trait ProjectFiles[+F[_]] {

  /**
    * Returns the specified project's plugin directory.
    *
    * @param owner  Owner name
    * @param name   Project name
    * @return       Plugin directory
    */
  def getProjectDir(owner: String, name: String): Path

  /**
    * Returns the specified version's directory
    *
    * @param owner   Owner name
    * @param name    Project name
    * @param version Version
    * @return        Version directory
    */
  def getVersionDir(owner: String, name: String, version: String): Path

  /**
    * Returns the specified user's plugin directory.
    *
    * @param user User name
    * @return     Plugin directory
    */
  def getUserDir(user: String): Path

  /**
    * Renames this specified project in the file system.
    *
    * @param owner    Owner name
    * @param oldName  Old project name
    * @param newName  New project name
    * @return         New path
    */
  def renameProject(owner: String, oldName: String, newName: String): F[Unit]

  /**
    * Transfers this specified project to a new user in the file system.
    *
    * @param oldOwner  Old owner name
    * @param newOwner  New owner name
    * @param name      Project name
    * @return          New path
    */
  def transferProject(oldOwner: String, newOwner: String, name: String): F[Unit]

  /**
    * Returns the directory that contains a [[Project]]'s custom icons.
    *
    * @param owner  Project owner
    * @param name   Project name
    * @return       Icons directory path
    */
  def getIconsDir(owner: String, name: String): Path

  /**
    * Returns the directory that contains a [[Project]]'s main icon.
    *
    * @param owner  Project owner
    * @param name   Project name
    * @return       Icon directory path
    */
  def getIconDir(owner: String, name: String): Path

  /**
    * Returns the path to a custom [[Project]] icon, if any, None otherwise.
    *
    * @param owner  Project owner
    * @param name   Project name
    * @return Project icon
    */
  def getIconPath(owner: String, name: String): F[Option[Path]]

  /**
    * Returns the path to a custom [[Project]] icon, if any, None otherwise.
    *
    * @param project Project to get icon for
    * @return Project icon
    */
  def getIconPath(project: Project): F[Option[Path]]
}
object ProjectFiles {

  class LocalProjectFiles[F[_]](val env: OreEnv)(implicit fileIO: FileIO[F], F: Bracket[F, Throwable])
      extends ProjectFiles[F] {

    override def getProjectDir(owner: String, name: String): Path = getUserDir(owner).resolve(name)

    override def getVersionDir(owner: String, name: String, version: String): Path =
      getProjectDir(owner, name).resolve("versions").resolve(version)

    override def getUserDir(user: String): Path = this.env.plugins.resolve(user)

    override def renameProject(owner: String, oldName: String, newName: String): F[Unit] = {
      val newProjectDir = getProjectDir(owner, newName)
      val oldProjectDir = getProjectDir(owner, oldName)

      F.ifM(fileIO.exists(oldProjectDir))(
        ifTrue = fileIO.move(oldProjectDir, newProjectDir),
        ifFalse = F.unit
      )
    }

    override def transferProject(oldOwner: String, newOwner: String, name: String): F[Unit] = {
      val newProjectDir = getProjectDir(newOwner, name)
      val oldProjectDir = getProjectDir(oldOwner, name)

      F.ifM(fileIO.exists(oldProjectDir))(
        ifTrue = fileIO.move(oldProjectDir, newProjectDir),
        ifFalse = F.unit
      )
    }

    override def getIconsDir(owner: String, name: String): Path = getProjectDir(owner, name).resolve("icons")

    override def getIconDir(owner: String, name: String): Path = getIconsDir(owner, name).resolve("icon")

    override def getIconPath(owner: String, name: String): F[Option[Path]] =
      findFirstFile(getIconDir(owner, name))

    override def getIconPath(project: Project): F[Option[Path]] =
      getIconPath(project.ownerName, project.name)

    private def findFirstFile(dir: Path): F[Option[Path]] = {
      val findFirst = fileIO.list(dir).use { fs =>
        fileIO.traverseLimited(fs)(f => fileIO.isDirectory(f).tupleLeft(f)).map {
          _.collectFirst {
            case (p, false) => p
          }
        }
      }

      fileIO.exists(dir).ifM(findFirst, F.pure(None))
    }
  }
}
