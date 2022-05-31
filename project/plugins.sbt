logLevel := Level.Warn
evictionWarningOptions in update := EvictionWarningOptions.default
  .withWarnTransitiveEvictions(false)
  .withWarnDirectEvictions(false)
  .withWarnScalaVersionEviction(false)

resolvers += "Typesafe repository".at("https://repo.typesafe.com/typesafe/releases/")

addSbtPlugin("com.typesafe.play" % "sbt-plugin"        % "2.8.8")
addSbtPlugin("com.typesafe.sbt"  % "sbt-digest"        % "1.1.4")
addSbtPlugin("com.typesafe.sbt"  % "sbt-gzip"          % "1.0.2")
addSbtPlugin("com.iheart"        %% "sbt-play-swagger" % "0.10.6-PLAY2.8")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo"     % "0.10.0")

libraryDependencies += "com.typesafe.play" %% "play-json" % "2.8.1"
