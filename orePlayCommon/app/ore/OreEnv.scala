package ore

import java.nio.file.{Path, Paths}

import play.api.Environment

/**
  * Helper class for getting commonly used Paths.
  */
final class OreEnv(val env: Environment, config: OreConfig) {

  lazy val root: Path    = this.env.rootPath.toPath
  lazy val public: Path  = this.root.resolve("public")
  lazy val conf: Path    = this.root.resolve("conf")
  lazy val uploads: Path = Paths.get(this.config.application.uploadsDir)
  lazy val plugins: Path = this.uploads.resolve("plugins")
  lazy val tmp: Path     = this.uploads.resolve("tmp")

}
