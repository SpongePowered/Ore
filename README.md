# Ore
![Build Status Ore](https://github.com/SpongePowered/Ore/workflows/Ore%20CI/badge.svg?branch=staging)
![Build Status Scalafmt](https://github.com/SpongePowered/Ore/workflows/Scalafmt%20Check/badge.svg?branch=staging)

Repository software for Sponge plugins and Forge mods https://ore.spongepowered.org/
 
Ore is written in Scala using the [Play](https://www.playframework.com/) framework.

### Clone
The following steps will ensure your project is cloned properly.

1. `git clone https://github.com/SpongePowered/Ore.git`   
2. `cp scripts/pre-commit .git/hooks`

### Setup

After cloning Ore, the first thing you will want to do is create a new PostgreSQL 11 database for the application to use.
This is required in order for Ore to run. Learn more about PostgreSQL [here](https://www.postgresql.org/).

You will also need to enable a few extensions for Ore. These are:
* [pgcrypto](https://www.postgresql.org/docs/11/pgcrypto.html)
* [hstore](https://www.postgresql.org/docs/11/hstore.html)

In addition, you need to install Node.js and Yarn. Installation instructions are available for [Node.js](https://nodejs.org/en/download/) and [Yarn](https://yarnpkg.com/lang/en/docs/install).

After setting up a database, create a copy of `application.conf.template` named `application.conf` and 
configure the application, for the application you want to run. This file is in the `.gitignore` so it will not appear in your commits.
Your local copy needs to get updated every time you pull changes, which add a new setting to the config. Currently valid 
applications are `ore` and `jobs`, and their configuration files can be found in `ore/conf/application.conf.template` and 
`jobs/src/main/resources/application.conf.template`.

In a typical development environment, most of the defaults are fine. Here are a few you might want to take a look at though.

For `ore`:
* You can disable authentication by setting `application.fakeUser` to `true`.

You also need to create a copy of `oreClient/src/main/resources/assets/config.json5.template` named `config.json5`. Try to mirror the values you used in the `application.conf` file here.

## Running

Running Ore is relatively simple.

**With SBT**
* Download and install the latest [SBT](http://www.scala-sbt.org/download.html) version.
* Execute `sbt ore/run` in the project root to run the web app.
* Execute `sbt jobs/run` in the project root to run the jobs processing.
* **Note:** You are advised to keep the sbt shell open when doing development instead of starting it for each task. 

**With IntelliJ Community Edition**
* Install the Scala plugin.
* Import the `build.sbt` file.

For `ore`:
* Create a new SBT Task run configuration. Enter `ore/run` in the Tasks field.
* Untick the box that says `Use sbt shell`
* Run it.

For `jobs`:
* Either repeat the process for `ore`, but use `jobs/run` instead of `ore/run`.
* Or, click the green triangle next to `OreJobProcessorMain` when opening up the file.

**Note:** You might encounter a stack overflow exception when compiling ore. This is not unexpected. Just assign 
more stack size to sbt in the way you're starting sbt. `-Xss4m` should be enough. If you're using IntelliJ, you can set 
this in the VM arguments field. If you're invoking sbt directly, the most common ways to set this is either through 
the `SBT_OPTS` environment variable, or with a file named `.jvmopts` with each flag on a new line.

### Running with Webpack dev server
Play can be a bit slow to reload the application sometimes. Therefor it can be nice to instead use webpack's hot module 
replacement. To do so, there are a few extra steps you need to go through.

1. Comment out the line with `webpackMonitoredDirectories` in `build.sbt` (not needed, just for sanity with reloading)
2. Add this to your `application.conf` under the filters section
```
cors {
    allowedOrigins = ["http://localhost:8080"]
}
```
3. If you want to see a logged in view, set `alwaysTryLogin` to `true` in `config.json5`
4. Start Ore like normal
5. Use `yarn run start` to start the webpack dev server
6. Navigate to `http://localhost:8080`

#### Disable webpack monitoring
By default, Ore will monitor the frontend for changes and rebuilt it whenever it sees any. If you're running Ore with 
the webpack server, this can be more of a hinderance. If you wish to disable it, add this to a new file 
called `user.sbt` in the root folder.
```scala
lazy val setNoMonitoredFiles: State => State = { s: State =>
  val projectID = "oreClient"
  val value = Nil
  if (Project.extract(s).get(LocalProject(projectID) / Assets / webpackMonitoredDirectories) == value)
    s
  else
    s"""set (LocalProject("$projectID") / Assets / webpackMonitoredDirectories) := $value""" :: s
}

Global / onLoad := {
  val old = (Global / onLoad).value
  setNoMonitoredFiles compose old
}
```
