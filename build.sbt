ThisBuild / turbo := true
ThisBuild / usePipelining := true
ThisBuild / scalaVersion := Settings.scalaVer

lazy val db = project.settings(
  Settings.commonSettings,
  name := "ore-db",
  libraryDependencies ++= Seq(
    Deps.slick,
    Deps.doobie,
    Deps.catsTagless,
    Deps.shapeless
  )
)

lazy val externalCommon = project.settings(
  Settings.commonSettings,
  name := "ore-external",
  libraryDependencies ++= Seq(
    Deps.cats,
    Deps.catsEffect,
    Deps.catsTagless,
    Deps.circe,
    Deps.circeDerivation,
    Deps.circeParser,
    Deps.circeYaml,
    Deps.tomlScala,
    Deps.akkaHttp,
    Deps.akkaHttpCore,
    Deps.akkaStream,
    Deps.akkaTyped,
    Deps.akkaSerializationJackson,
    Deps.scalaLogging,
    Deps.simulacrum
  )
)

lazy val discourse = project
  .dependsOn(externalCommon)
  .settings(
    Settings.commonSettings,
    name := "ore-discourse"
  )

lazy val auth = project
  .dependsOn(externalCommon)
  .settings(
    Settings.commonSettings,
    name := "ore-auth"
  )

lazy val models = project
  .dependsOn(db)
  .settings(
    Settings.commonSettings,
    name := "ore-models",
    libraryDependencies ++= Seq(
      Deps.postgres,
      Deps.slickPg,
      Deps.slickPgCirce,
      Deps.doobiePostgres,
      Deps.doobiePostgresCirce,
      Deps.scalaLogging,
      Deps.enumeratum,
      Deps.enumeratumSlick,
      Deps.cats,
      Deps.simulacrum,
      Deps.circe,
      Deps.circeDerivation,
      Deps.circeParser
    )
  )

lazy val jobs = project
  .enablePlugins(UniversalPlugin, JavaAppPackaging, ExternalizedResourcesMappings)
  .dependsOn(models, discourse)
  .settings(
    Settings.commonSettings,
    name := "ore-jobs",
    libraryDependencies ++= Seq(
      Deps.zio,
      Deps.zioCats,
      Deps.slickHikariCp,
      Deps.scalaLogging,
      Deps.logback,
      Deps.sentry,
      Deps.pureConfig
    )
  )

lazy val orePlayCommon: Project = project
  .enablePlugins(PlayScala)
  .dependsOn(auth, models)
  .settings(
    Settings.commonSettings,
    Settings.playCommonSettings,
    name := "ore-play-common",
    resolvers += "sponge".at("https://repo.spongepowered.org/maven"),
    libraryDependencies ++= Seq(caffeine, ws),
    libraryDependencies ++= Seq(
      Deps.pluginMeta,
      Deps.pluginMetaMcMod,
      Deps.slickPlay,
      Deps.zio,
      Deps.zioCats,
      Deps.pureConfig
    ),
    aggregateReverseRoutes := Seq(ore)
  )

lazy val apiV2 = project
  .enablePlugins(PlayScala)
  .dependsOn(orePlayCommon)
  .settings(
    Settings.commonSettings,
    Settings.playCommonSettings,
    name := "ore-apiv2",
    routesImport ++= Seq(
      "util.APIBinders._"
    ).map(s => s"_root_.$s"),
    libraryDependencies ++= Seq(
      Deps.scalaLogging,
      Deps.circe,
      Deps.circeDerivation,
      Deps.circeParser,
      Deps.scalaCache,
      Deps.scalaCacheCatsEffect,
      Deps.perspectiveDerivation,
      Deps.perspectiveMacros
    ),
    libraryDependencies ++= Deps.playTestDeps
  )

lazy val oreClient = project
  .enablePlugins(WebpackPlugin)
  .settings(
    Settings.commonSettings,
    name := "ore-client",
    Assets / webpackDevConfig := baseDirectory.value / "webpack.config.dev.js",
    Assets / webpackProdConfig := baseDirectory.value / "webpack.config.prod.js",
    Assets / webpackMonitoredDirectories += baseDirectory.value / "src" / "main" / "assets",
    Assets / webpackMonitoredFiles / includeFilter := "*.vue" || "*.js",
    Assets / webpackMonitoredFiles ++= Seq(
      baseDirectory.value / "webpack.config.common.js",
      baseDirectory.value / ".postcssrc.js",
      baseDirectory.value / ".browserlistrc"
    ),
    pipelineStages := Seq(digest, gzip)
  )

lazy val ore = project
  .enablePlugins(PlayScala, SwaggerPlugin, BuildInfoPlugin)
  .dependsOn(orePlayCommon, apiV2, oreClient)
  .settings(
    Settings.commonSettings,
    Settings.playCommonSettings,
    name := "ore",
    libraryDependencies ++= Seq(
      Deps.slickPlayEvolutions,
      Deps.scalaLogging,
      Deps.sentry,
      Deps.javaxMail,
      Deps.circe,
      Deps.circeDerivation,
      Deps.circeParser,
      Deps.macwire,
      Deps.periscopeAkka
    ),
    libraryDependencies ++= Deps.flexmarkDeps,
    libraryDependencies ++= Seq(
      WebjarsDeps.jQuery,
      WebjarsDeps.moment,
      WebjarsDeps.chartJs,
      WebjarsDeps.swaggerUI
    ),
    libraryDependencies ++= Deps.playTestDeps,
    swaggerRoutesFile := "apiv2.routes",
    swaggerDomainNameSpaces := Seq(
      "models.protocols.APIV2",
      "controllers.apiv2.AbstractApiV2Controller",
      "controllers.apiv2.Users",
      "controllers.apiv2.Authentication",
      "controllers.apiv2.Keys",
      "controllers.apiv2.Permissions",
      "controllers.apiv2.Projects",
      "controllers.apiv2.Users",
      "controllers.apiv2.Versions",
      "controllers.apiv2.Members",
      "controllers.apiv2.helpers"
    ),
    swaggerNamingStrategy := "snake_case",
    swaggerAPIVersion := "2.0",
    swaggerV3 := true,
    PlayKeys.playMonitoredFiles += baseDirectory.value / "swagger.yml",
    PlayKeys.playMonitoredFiles += baseDirectory.value / "swagger-custom-mappings.yml",
    Assets / WebKeys.exportedMappings := Seq(),
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, resolvers, libraryDependencies),
    buildInfoOptions += BuildInfoOption.BuildTime,
    buildInfoPackage := "ore",
    //sbt 1.4 workaround
    play.sbt.PlayInternalKeys.playCompileEverything ~= (_.map(
      _.copy(compilations = sbt.internal.inc.Compilations.of(Seq.empty))
    ))
  )

lazy val oreAll =
  project
    .in(file("."))
    .aggregate(db, externalCommon, discourse, auth, models, orePlayCommon, apiV2, ore, oreClient, jobs)
