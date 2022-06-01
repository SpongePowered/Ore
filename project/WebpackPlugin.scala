import java.io.{File, InputStream}

import scala.sys.process.{BasicIO, Process}
import scala.util.{Failure, Success, Try}

import play.api.libs.json.{JsError, JsSuccess, Json}

import Stats.WebpackStats
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.SbtWeb.autoImport._
import sbt.Keys._
import sbt._

//Webpack parts of scalajs-bundler extracted out into it's own plugin
object WebpackPlugin extends AutoPlugin {

  override def requires = SbtWeb

  object autoImport {

    val yarnInstall   = taskKey[File]("Runs yarn install")
    val yarnExtraArgs = settingKey[Seq[String]]("Extra settings passed to yarn")

    val webpack    = taskKey[Seq[File]]("Runs webpack")
    val webpackDev = taskKey[Seq[File]]("Runs webpack in development mode")

    val webpackDevConfig  = settingKey[File]("Config file to use with webpack for development")
    val webpackProdConfig = settingKey[File]("Config file to use with webpack for production")

    val webpackMonitoredFiles = settingKey[Seq[File]]("Files that sbt should watch for changes before invoking webpack")
    val webpackMonitoredDirectories =
      settingKey[Seq[File]]("Directories that sbt should watch for changes before invoking webpack")

    val webpackExtraArgs = settingKey[Seq[String]]("Extra arguments to pass to webpack")
    val webpackNodeArgs  = settingKey[Seq[String]]("Arguments to pass to node when launching webpack")
  }
  import autoImport._

  val devCommands = settingKey[Seq[String]]("Commands used for development")

  //https://github.com/vmunier/sbt-web-scalajs/blob/master/src/main/scala-sbt-1.0/webscalajs/CrossSbtUtils.scala
  lazy val executedCommandKey = Def.task {
    // A fully-qualified reference to a setting or task looks like {<build-uri>}<project-id>/config:intask::key
    state.value.history.currentOption
      .flatMap(_.commandLine.takeWhile(c => !c.isWhitespace).split(Array('/', ':')).lastOption)
      .getOrElse("")
  }

  private lazy val isDevModeTask: Def.Initialize[Task[Boolean]] = Def.task {
    (webpack / devCommands).value.contains(executedCommandKey.value)
  }

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    webpack / devCommands := Seq("run", "compile", "re-start", "reStart", "runAll", "webpackDev"),
    webpackMonitoredDirectories := Seq.empty,
    (Assets / webpackMonitoredFiles / includeFilter) := AllPassFilter,
    (Assets / webpackMonitoredFiles / excludeFilter) := NothingFilter,
    webpackExtraArgs := Seq.empty,
    webpackNodeArgs := Seq.empty,
    yarnExtraArgs := Seq.empty,
    Assets / sourceGenerators += webpack.taskValue,
    yarnInstall := {
      val baseDir    = baseDirectory.value
      val streamsObj = streams.value
      val extraArgs  = yarnExtraArgs.value

      val packageJsonFile = baseDir / "package.json"

      val log = streamsObj.log
      val cachedActionFunction =
        FileFunction.cached(
          streamsObj.cacheDirectory / "webpack-yarn-install",
          inStyle = FilesInfo.hash
        ) { _ =>
          log.info("Updating Yarn dependencies")
          Yarn.runInstall(baseDir, log, extraArgs)
          Set.empty
        }
      cachedActionFunction(Set(packageJsonFile))
      baseDir
    },
    webpack := webpackTask.value,
    webpackDev := webpackTask.value,
    Assets / webpackMonitoredFiles := {
      val webpackDevConfigFile  = (Assets / webpackDevConfig).value
      val webpackProdConfigFile = (Assets / webpackProdConfig).value
      val include               = (Assets / webpackMonitoredFiles / includeFilter).value
      val exclude               = (Assets / webpackMonitoredFiles / excludeFilter).value
      val dirs                  = (Assets / webpackMonitoredDirectories).value

      val additionalFiles: Seq[File] = dirs.flatMap(dir => (dir ** include).filter(!exclude.accept(_)).get)
      webpackDevConfigFile +: webpackProdConfigFile +: additionalFiles
    }
  )

  val webpackTask = Def.task {
    val isDevMode             = isDevModeTask.value
    val cacheLocation         = streams.value.cacheDirectory / s"webpack"
    val webpackDevConfigFile  = (Assets / webpackDevConfig).value
    val webpackProdConfigFile = (Assets / webpackProdConfig).value
    val workingDir            = yarnInstall.value
    val log                   = streams.value.log
    val monitoredFiles        = (Assets / webpackMonitoredFiles).value
    val extraArgs             = (Assets / webpackExtraArgs).value
    val nodeArgs              = (Assets / webpackNodeArgs).value

    val webpackConfigFile = if (isDevMode) webpackDevConfigFile else webpackProdConfigFile

    val cachedActionFunction = FileFunction.cached(cacheLocation, inStyle = FileInfo.hash)(_ =>
      Webpack.runWebpack(webpackConfigFile, workingDir, extraArgs, nodeArgs, log).toSet
    )

    cachedActionFunction(monitoredFiles.toSet).toSeq
  }

  object Yarn {
    private val yarnOptions = List("--non-interactive", "--mutex", "network")

    def runInstall(
        baseDir: File,
        logger: Logger,
        yarnExtraArgs: Seq[String]
    ): Unit = {
      val cmd = sys.props("os.name").toLowerCase match {
        case os if os.contains("win") => Seq("cmd", "/c", "yarn")
        case _                        => Seq("yarn")
      }

      Commands.run(cmd ++: "install" +: (yarnOptions ++ yarnExtraArgs), baseDir, logger)
    }
  }

  object Webpack {
    def runWebpack(
        configFile: File,
        workingDir: File,
        extraArgs: Seq[String],
        nodeArgs: Seq[String],
        log: Logger
    ): List[File] = {
      val args = extraArgs ++: Seq("--config", configFile.absolutePath)

      val webpackBin = workingDir / "node_modules" / "webpack" / "bin" / "webpack"
      val params     = nodeArgs ++ Seq(webpackBin.absolutePath, "--bail", "--profile", "--json") ++ args
      val cmd        = "node" +: params
      val stats      = Commands.run(cmd, workingDir, log, jsonOutput(cmd, log)).fold(sys.error, _.flatten)
      stats.foreach(_.print(log))

      stats.map(s => s.resolveAllAssets(workingDir.toPath)).getOrElse(Nil)
    }

    private def jsonOutput(cmd: Seq[String], logger: Logger)(in: InputStream): Option[WebpackStats] = {
      Try {
        val parsed = Json.parse(in)
        parsed.validate[WebpackStats] match {
          case JsError(e) =>
            logger.error("Error parsing webpack stats output")
            // In case of error print the result and return None. it will be ignored upstream
            e.foreach {
              case (p, v) => logger.error(s"$p: ${v.mkString(", ")}")
            }
            None
          case JsSuccess(p, _) =>
            if (p.warnings.nonEmpty || p.errors.nonEmpty) {
              logger.info("")
              // Filtering is a workaround for #111
              p.warnings
                .filterNot(_.stack.contains("https://raw.githubusercontent.com"))
                .foreach(x => x.print(Level.Warn, logger))
              p.errors.foreach(x => x.print(Level.Error, logger))
            }
            Some(p)
        }
      } match {
        case Success(x) =>
          x
        case Failure(e) =>
          // In same cases errors are not reported on the json output but comes on stdout
          // where they cannot be parsed as json. The best we can do here is to suggest
          // running the command manually
          logger.error(s"Failure on parsing the output of webpack: ${e.getMessage}")
          logger.error(s"You can try to manually execute the command")
          logger.error(cmd.mkString(" "))
          logger.error("\n")
          None
      }
    }
  }

  object Commands {
    def run[A](
        cmd: Seq[String],
        cwd: File,
        logger: Logger,
        outputProcess: InputStream => A
    ): Either[String, Option[A]] = {
      val toErrorLog = (is: InputStream) => {
        scala.io.Source.fromInputStream(is).getLines.foreach(msg => logger.error(msg))
        is.close()
      }

      // Unfortunately a var is the only way to capture the result
      var result: Option[A] = None
      def outputCapture(o: InputStream): Unit = {
        result = Some(outputProcess(o))
        o.close()
        ()
      }

      logger.debug(s"Command: ${cmd.mkString(" ")}")
      val process   = Process(cmd, cwd, "FROM_SBT" -> "true")
      val processIO = BasicIO.standard(false).withOutput(outputCapture).withError(toErrorLog)
      val code: Int = process.run(processIO).exitValue()
      if (code != 0) {
        Left(s"Non-zero exit code: $code")
      } else {
        Right(result)
      }
    }

    def run(cmd: Seq[String], cwd: File, logger: Logger): Unit = {
      val toInfoLog = (is: InputStream) => scala.io.Source.fromInputStream(is).getLines.foreach(msg => logger.info(msg))
      run(cmd, cwd, logger, toInfoLog).fold(sys.error, _ => ())
    }
  }
}
