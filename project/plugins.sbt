logLevel := Level.Warn
evictionWarningOptions in update := EvictionWarningOptions.default
  .withWarnTransitiveEvictions(false)
  .withWarnDirectEvictions(false)
  .withWarnScalaVersionEviction(false)

addSbtPlugin("com.typesafe.play" % "sbt-plugin"              % "2.8.16")
addSbtPlugin("com.typesafe.sbt"  % "sbt-digest"              % "1.1.4")
addSbtPlugin("com.typesafe.sbt"  % "sbt-gzip"                % "1.0.2")
addSbtPlugin("com.iheart"        %% "sbt-play-swagger"       % "0.10.7-PLAY2.8")
addSbtPlugin("org.scala-js"      % "sbt-scalajs"             % "1.6.0")
addSbtPlugin("ch.epfl.scala"     % "sbt-web-scalajs-bundler" % "0.20.0")
addSbtPlugin("com.eed3si9n"      % "sbt-buildinfo"           % "0.11.0")
