import play.sbt.PlayImport._

import sbt._

object Version {
  val cats        = "2.6.1"
  val catsEffect  = "2.2.0"
  val catsTagless = "0.11"

  val zio     = "1.0.9"
  val zioCats = "2.2.0.1"

  val slick     = "3.3.3"
  val slickPg   = "0.19.6"
  val playSlick = "5.0.0"
  val doobie    = "0.9.4"

  val circe           = "0.13.0"
  val circeDerivation = "0.13.0-M5"

  val akka         = "2.6.15"
  val akkaHttp     = "10.1.15"
  val scalaLogging = "3.9.4"

  val simulacrum = "0.19.0"
  val macWire    = "2.3.7"
  val scalaCache = "0.28.0"
  val flexmark   = "0.62.2"
}

object Deps {

  val cats        = "org.typelevel"        %% "cats-core"           % Version.cats
  val catsEffect  = "org.typelevel"        %% "cats-effect"         % Version.catsEffect
  val catsTagless = "org.typelevel"        %% "cats-tagless-macros" % Version.catsTagless
  val shapeless   = "com.chuusai"          %% "shapeless"           % "2.3.7"
  val simulacrum  = "com.github.mpilquist" %% "simulacrum"          % Version.simulacrum

  val slick               = "com.typesafe.slick"  %% "slick"                 % Version.slick
  val slickHikariCp       = "com.typesafe.slick"  %% "slick-hikaricp"        % Version.slick
  val slickPlay           = "com.typesafe.play"   %% "play-slick"            % Version.playSlick
  val slickPlayEvolutions = "com.typesafe.play"   %% "play-slick-evolutions" % Version.playSlick
  val slickPg             = "com.github.tminglei" %% "slick-pg"              % Version.slickPg
  val slickPgCirce        = "com.github.tminglei" %% "slick-pg_circe-json"   % Version.slickPg

  val doobie              = "org.tpolecat" %% "doobie-core"           % Version.doobie
  val doobiePostgres      = "org.tpolecat" %% "doobie-postgres"       % Version.doobie
  val doobiePostgresCirce = "org.tpolecat" %% "doobie-postgres-circe" % Version.doobie

  val circe           = "io.circe" %% "circe-core"                   % Version.circe
  val circeDerivation = "io.circe" %% "circe-derivation-annotations" % Version.circeDerivation
  val circeParser     = "io.circe" %% "circe-parser"                 % Version.circe

  val akkaHttp                 = "com.typesafe.akka" %% "akka-http"                  % Version.akkaHttp
  val akkaHttpCore             = "com.typesafe.akka" %% "akka-http-core"             % Version.akkaHttp
  val akkaStream               = "com.typesafe.akka" %% "akka-stream"                % Version.akka
  val akkaTyped                = "com.typesafe.akka" %% "akka-actor-typed"           % Version.akka
  val akkaSerializationJackson = "com.typesafe.akka" %% "akka-serialization-jackson" % Version.akka

  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % Version.scalaLogging

  val enumeratum      = "com.beachape" %% "enumeratum"       % "1.6.1"
  val enumeratumSlick = "com.beachape" %% "enumeratum-slick" % "1.6.0"

  val zio     = "dev.zio" %% "zio"              % Version.zio
  val zioCats = "dev.zio" %% "zio-interop-cats" % Version.zioCats

  val pureConfig = "com.github.pureconfig" %% "pureconfig" % "0.14.1"

  val scalaCache           = "com.github.cb372" %% "scalacache-caffeine"    % Version.scalaCache
  val scalaCacheCatsEffect = "com.github.cb372" %% "scalacache-cats-effect" % Version.scalaCache

  val macwire = "com.softwaremill.macwire" %% "macros" % Version.macWire % "provided"

  val periscopeAkka = "io.scalac" %% "akka-periscope-core" % "0.4.0"

  private def flexmarkDep(module: String) = {
    val artifactId = if (module.isEmpty) "flexmark" else s"flexmark-$module"
    "com.vladsch.flexmark" % artifactId % Version.flexmark
  }

  val flexmarkDeps: Seq[ModuleID] = Seq(
    "",
    "ext-autolink",
    "ext-anchorlink",
    "ext-gfm-strikethrough",
    "ext-gfm-tasklist",
    "ext-tables",
    "ext-typographic",
    "ext-wikilink"
  ).map(flexmarkDep)

  // Sponge API-8+
  val pluginMeta = "org.spongepowered" % "plugin-meta" % "0.8.0"

  // mcmod.info
  val pluginMetaMcMod = "org.spongepowered.plugin-meta" % "mcmod-info" % "0.8.0"

  val javaxMail = "javax.mail"     % "mail"            % "1.4.7"
  val postgres  = "org.postgresql" % "postgresql"      % "42.2.16"
  val logback   = "ch.qos.logback" % "logback-classic" % "1.2.3"
  val sentry    = "io.sentry"      % "sentry-logback"  % "3.2.1"

  val playTestDeps = Seq(
    jdbc % Test,
    //specs2 % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0"        % Test,
    "org.scalatestplus"      %% "junit-4-12"         % "3.2.2.0"      % Test,
    "org.tpolecat"           %% "doobie-scalatest"   % Version.doobie % Test
  )
}
