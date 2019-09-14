lazy val commonSettings = Seq(
  version := "1.8.2",
  scalaVersion := "2.13.0",
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "utf-8",
    "-explaintypes",
    "-feature",
    "-unchecked",
    "-Xcheckinit",
    //"-Werror",
    "-Xlint:adapted-args",
    "-Xlint:constant",
    "-Xlint:delayedinit-select",
    "-Xlint:doc-detached",
    "-Xlint:inaccessible",
    "-Xlint:infer-any",
    "-Xlint:missing-interpolator",
    "-Xlint:nullary-override",
    "-Xlint:nullary-unit",
    "-Xlint:option-implicit",
    "-Xlint:package-object-classes",
    "-Xlint:poly-implicit-overload",
    "-Xlint:private-shadow",
    "-Xlint:stars-align",
    "-Xlint:type-parameter-shadow",
    "-Xlint:infer-any",
    "-Wdead-code",
    "-Wnumeric-widen",
    "-Wunused:params",
    "-Wunused:locals",
    "-Wunused:patvars",
    "-Wunused:privates",
    "-Wvalue-discard",
    "-Yrangepos",
    "-Ymacro-annotations",
    "-Ybackend-parallelism",
    "6"
  ),
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
  // Disable generation of the API documentation for production builds
  sources in (Compile, doc) := Seq.empty,
  publishArtifact in (Compile, packageDoc) := false
)

ThisBuild / turbo := true

//ThisBuild / semanticdbEnabled := true
Global / semanticdbVersion := "4.2.3"

lazy val playCommonSettings = Seq(
  routesImport ++= Seq(
    "ore.db.DbRef",
    "ore.models.admin._",
    "ore.models.project._",
    "ore.models.user._",
    "ore.models.user.role._",
    "ore.permission.NamedPermission",
    "ore.data.project.Category"
  ).map(s => s"_root_.$s"),
  unmanagedResourceDirectories in Test += (baseDirectory.value / "target/web/public/test"),
  pipelineStages := Seq(digest, gzip)
)

lazy val playTestDeps = Seq(
  jdbc % Test,
  //specs2 % Test,
  "org.scalatestplus.play" %% "scalatestplus-play" % "4.0.3"       % Test,
  "org.tpolecat"           %% "doobie-scalatest"   % doobieVersion % Test
)

lazy val catsVersion         = "2.0.0"
lazy val catsEffectVersion   = "2.0.0"
lazy val catsTaglessVersion  = "0.9"
lazy val zioVersion          = "1.0.0-RC12-1"
lazy val zioCatsVersion      = "2.0.0.0-RC3"
lazy val doobieVersion       = "0.8.0-RC1"
lazy val flexmarkVersion     = "0.50.40"
lazy val playSlickVersion    = "4.0.2"
lazy val slickPgVersion      = "0.18.0"
lazy val circeVersion        = "0.12.1"
lazy val akkaVersion         = "2.5.23"
lazy val akkaHttpVersion     = "10.1.9"
lazy val scalaLoggingVersion = "3.9.2"
lazy val simulacrumVersion   = "0.19.0"
lazy val macWireVersion      = "2.3.3"
lazy val scalaCacheVersion   = "0.28.0"

lazy val db = project.settings(
  commonSettings,
  name := "ore-db",
  libraryDependencies ++= Seq(
    "com.typesafe.slick" %% "slick"               % "3.3.2",
    "org.tpolecat"       %% "doobie-core"         % doobieVersion,
    "org.typelevel"      %% "cats-tagless-macros" % catsTaglessVersion,
    "com.chuusai"        %% "shapeless"           % "2.3.3"
  )
)

lazy val externalCommon = project.settings(
  commonSettings,
  name := "ore-external",
  libraryDependencies ++= Seq(
    "org.typelevel"              %% "cats-core"            % catsVersion,
    "org.typelevel"              %% "cats-effect"          % catsEffectVersion,
    "org.typelevel"              %% "cats-tagless-macros"  % catsTaglessVersion,
    "io.circe"                   %% "circe-core"           % circeVersion,
    "io.circe"                   %% "circe-generic-extras" % circeVersion,
    "io.circe"                   %% "circe-parser"         % circeVersion,
    "com.typesafe.akka"          %% "akka-http"            % akkaHttpVersion,
    "com.typesafe.akka"          %% "akka-http-core"       % akkaHttpVersion,
    "com.typesafe.akka"          %% "akka-stream"          % akkaVersion,
    "com.typesafe.scala-logging" %% "scala-logging"        % scalaLoggingVersion,
    "com.github.mpilquist"       %% "simulacrum"           % simulacrumVersion
  )
)

lazy val discourse = project
  .dependsOn(externalCommon)
  .settings(
    commonSettings,
    name := "ore-discourse"
  )

lazy val auth = project
  .dependsOn(externalCommon)
  .settings(
    commonSettings,
    name := "ore-auth"
  )

lazy val models = project
  .dependsOn(db)
  .settings(
    commonSettings,
    name := "ore-models",
    libraryDependencies ++= Seq(
      "org.postgresql"             % "postgresql"             % "42.2.7",
      "com.github.tminglei"        %% "slick-pg"              % slickPgVersion,
      "com.github.tminglei"        %% "slick-pg_circe-json"   % slickPgVersion,
      "org.tpolecat"               %% "doobie-postgres"       % doobieVersion,
      "org.tpolecat"               %% "doobie-postgres-circe" % doobieVersion,
      "com.typesafe.scala-logging" %% "scala-logging"         % scalaLoggingVersion,
      "com.beachape"               %% "enumeratum"            % "1.5.13",
      "com.beachape"               %% "enumeratum-slick"      % "1.5.16",
      "org.typelevel"              %% "cats-core"             % catsVersion,
      "com.github.mpilquist"       %% "simulacrum"            % simulacrumVersion,
      "io.circe"                   %% "circe-core"            % circeVersion,
      "io.circe"                   %% "circe-generic-extras"  % circeVersion,
      "io.circe"                   %% "circe-parser"          % circeVersion
    )
  )

lazy val orePlayCommon: Project = project
  .enablePlugins(PlayScala)
  .dependsOn(discourse, auth, models)
  .settings(
    commonSettings,
    playCommonSettings,
    name := "ore-play-common",
    resolvers += "sponge".at("https://repo.spongepowered.org/maven"),
    libraryDependencies ++= Seq(caffeine, ws),
    libraryDependencies ++= Seq(
      "org.spongepowered" % "plugin-meta" % "0.4.1",
      "com.typesafe.play" %% "play-slick" % playSlickVersion
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"              % zioVersion,
      "dev.zio" %% "zio-interop-cats" % zioCatsVersion
    ),
    aggregateReverseRoutes := Seq(ore)
  )

lazy val apiV2 = project
  .enablePlugins(PlayScala)
  .dependsOn(orePlayCommon)
  .settings(
    commonSettings,
    playCommonSettings,
    name := "ore-apiv2",
    routesImport ++= Seq(
      "util.APIBinders._"
    ).map(s => s"_root_.$s"),
    libraryDependencies ++= Seq(
      "com.typesafe.scala-logging" %% "scala-logging"          % scalaLoggingVersion,
      "org.typelevel"              %% "cats-core"              % catsVersion,
      "io.circe"                   %% "circe-core"             % circeVersion,
      "io.circe"                   %% "circe-generic-extras"   % circeVersion,
      "io.circe"                   %% "circe-parser"           % circeVersion,
      "com.github.cb372"           %% "scalacache-caffeine"    % scalaCacheVersion,
      "com.github.cb372"           %% "scalacache-cats-effect" % scalaCacheVersion
    ),
    libraryDependencies ++= playTestDeps
  )

def flexmarkDep(module: String) = {
  val artifactId = if (module.isEmpty) "flexmark" else s"flexmark-$module"
  "com.vladsch.flexmark" % artifactId % flexmarkVersion
}

lazy val oreClient = project
  .enablePlugins(ScalaJSBundlerPlugin)
  .settings(
    name := "ore-client",
    commonSettings,
    useYarn := true,
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    webpackConfigFile in fastOptJS := Some(baseDirectory.value / "webpack.config.dev.js"),
    webpackConfigFile in fullOptJS := Some(baseDirectory.value / "webpack.config.prod.js"),
    webpackMonitoredDirectories += baseDirectory.value / "assets",
    includeFilter in webpackMonitoredFiles := "*.vue" || "*.js",
    webpackBundlingMode in fastOptJS := BundlingMode.LibraryOnly(),
    webpackBundlingMode in fullOptJS := BundlingMode.LibraryOnly(),
    version in startWebpackDevServer := "3.7.2",
    version in webpack := "4.36.1",
    npmDependencies in Compile ++= Seq(
      "vue"                                 -> "2.6.10",
      "lodash"                              -> "4.17.15",
      "query-string"                        -> "6.8.1",
      "@fortawesome/fontawesome-svg-core"   -> "1.2.19",
      "@fortawesome/free-solid-svg-icons"   -> "5.9.0",
      "@fortawesome/free-regular-svg-icons" -> "5.9.0",
      "@fortawesome/free-brands-svg-icons"  -> "5.9.0"
    ),
    npmDevDependencies in Compile ++= Seq(
      "webpack-merge"                      -> "4.2.1",
      "vue-loader"                         -> "15.7.1",
      "vue-template-compiler"              -> "2.6.10",
      "css-loader"                         -> "3.1.0",
      "vue-style-loader"                   -> "4.1.2",
      "babel-loader"                       -> "8.0.6",
      "@babel/core"                        -> "7.5.5",
      "@babel/preset-env"                  -> "7.5.5",
      "terser-webpack-plugin"              -> "1.3.0",
      "mini-css-extract-plugin"            -> "0.8.0",
      "optimize-css-assets-webpack-plugin" -> "5.0.3",
      "sass-loader"                        -> "7.1.0",
      "postcss-loader"                     -> "3.0.0",
      "autoprefixer"                       -> "9.6.1",
      "node-sass"                          -> "4.12.0",
      "copy-webpack-plugin"                -> "5.0.3"
    )
  )

lazy val ore = project
  .enablePlugins(PlayScala, SwaggerPlugin, WebScalaJSBundlerPlugin)
  .dependsOn(orePlayCommon, apiV2)
  .settings(
    commonSettings,
    playCommonSettings,
    name := "ore",
    libraryDependencies ++= Seq(
      "com.typesafe.play"          %% "play-slick-evolutions" % playSlickVersion,
      "com.typesafe.scala-logging" %% "scala-logging"         % scalaLoggingVersion,
      "io.sentry"                  % "sentry-logback"         % "1.7.27",
      "javax.mail"                 % "mail"                   % "1.4.7",
      "org.typelevel"              %% "cats-core"             % catsVersion,
      "io.circe"                   %% "circe-core"            % circeVersion,
      "io.circe"                   %% "circe-generic-extras"  % circeVersion,
      "io.circe"                   %% "circe-parser"          % circeVersion,
      "com.softwaremill.macwire"   %% "macros"                % macWireVersion % "provided",
      "com.softwaremill.macwire"   %% "macrosakka"            % macWireVersion % "provided"
    ),
    libraryDependencies ++= Seq(
      "",
      "ext-autolink",
      "ext-anchorlink",
      "ext-gfm-strikethrough",
      "ext-gfm-tasklist",
      "ext-tables",
      "ext-typographic",
      "ext-wikilink"
    ).map(flexmarkDep),
    libraryDependencies ++= Seq(
      "org.webjars.npm" % "jquery"       % "2.2.4",
      "org.webjars"     % "font-awesome" % "5.10.1",
      "org.webjars.npm" % "filesize"     % "3.6.1",
      "org.webjars.npm" % "moment"       % "2.24.0",
      "org.webjars.npm" % "clipboard"    % "2.0.4",
      "org.webjars.npm" % "chart.js"     % "2.8.0",
      "org.webjars"     % "swagger-ui"   % "3.23.8"
    ),
    libraryDependencies ++= playTestDeps,
    swaggerRoutesFile := "apiv2.routes",
    swaggerDomainNameSpaces := Seq(
      "models.protocols.APIV2",
      "controllers.apiv2.ApiV2Controller"
    ),
    swaggerAPIVersion := "2.0",
    swaggerV3 := true,
    scalaJSProjects := Seq(oreClient),
    pipelineStages in Assets += scalaJSPipeline,
    WebKeys.exportedMappings in Assets := Seq(),
    PlayKeys.playMonitoredFiles += (oreClient / baseDirectory).value / "assets"
  )

lazy val oreAll =
  project.in(file(".")).aggregate(db, externalCommon, discourse, auth, models, orePlayCommon, apiV2, ore, oreClient)
