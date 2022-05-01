package ore

import scala.concurrent.duration._

import ore.data.Color
import ore.db.DbRef
import ore.models.project.Channel
import ore.models.user.User
import ore.util.StringUtils._

case class OreConfig(
    application: OreConfig.App,
    ore: OreConfig.Ore,
    sponge: OreConfig.Sponge,
    auth: OreConfig.Auth,
    mail: OreConfig.Mail,
    performance: OreConfig.Performance
) {

  /**
    * The default color used for Channels.
    */
  val defaultChannelColor: Color = Channel.Colors(ore.channels.colorDefault)

  /**
    * The default name used for Channels.
    */
  val defaultChannelName: String = ore.channels.nameDefault

  /**
    * Returns true if the specified name is a valid Project name.
    *
    * @param name   Name to check
    * @return       True if valid name
    */
  def isValidProjectName(name: String): Boolean = {
    val sanitized = compact(name)
    sanitized.length >= 1 && sanitized.length <= ore.projects.maxNameLen
  }

  /**
    * Returns true if the specified string is a valid channel name.
    *
    * @param name   Name to check
    * @return       True if valid channel name
    */
  def isValidChannelName(name: String): Boolean = {
    val c = ore.channels
    name.length >= 1 && name.length <= c.maxNameLen && name.matches(c.nameRegex)
  }

  /** Returns true if the application is running in debug mode. */
  def isDebug: Boolean = this.ore.debug

  /** Asserts that the application is in debug mode. */
  def checkDebug(): Unit =
    if (!isDebug)
      throw new UnsupportedOperationException("this function is supported in debug mode only") // scalafix:ok
}
object OreConfig {
  case class App(
      baseUrl: String,
      discourseUrl: String,
      discourseCdnUrl: String,
      uploadsDir: String,
      trustedUrlHosts: Seq[String],
      fakeUser: App.OreConfigFakeUser
  )
  object App {
    case class OreConfigFakeUser(
        enabled: Boolean,
        id: DbRef[User],
        name: Option[String],
        username: String,
        email: Option[String]
    )
  }

  case class Ore(
      debug: Boolean,
      debugLevel: Int,
      staging: Boolean,
      logTimings: Boolean,
      homepage: Ore.Homepage,
      channels: Ore.Channels,
      pages: Ore.Pages,
      projects: Ore.Projects,
      users: Ore.Users,
      orgs: Ore.Orgs,
      queue: Ore.Queue,
      api: Ore.Api,
      session: Ore.Session
  )
  object Ore {
    case class Homepage(
        updateInterval: FiniteDuration
    )

    case class Channels(
        maxNameLen: Int,
        nameRegex: String,
        colorDefault: Int,
        nameDefault: String
    )

    case class Pages(
        homeName: String,
        homeMessage: String,
        minLen: Int,
        maxLen: Int,
        pageMaxLen: Int
    )

    case class Projects(
        maxNameLen: Int,
        maxPages: Int,
        maxChannels: Int,
        initLoad: Long,
        initVersionLoad: Int,
        maxDescLen: Int,
        fileValidate: Boolean,
        staleAge: FiniteDuration,
        checkInterval: FiniteDuration,
        draftExpire: FiniteDuration,
        userGridPageSize: Int,
        warnOnUnsafeDownloads: Boolean,
        unsafeDownloadMaxAge: FiniteDuration
    )

    case class Users(
        starsPerPage: Int,
        maxTaglineLen: Int,
        authorPageSize: Long,
        projectPageSize: Long
    )

    case class Orgs(
        enabled: Boolean,
        dummyEmailDomain: String,
        createLimit: Int
    )

    case class Queue(
        maxReviewTime: FiniteDuration
    )

    case class Api(
        session: Api.Session
    )
    object Api {
      case class Session(
          publicExpiration: FiniteDuration,
          expiration: FiniteDuration,
          checkInterval: FiniteDuration
      )
    }

    case class Session(
        secure: Boolean,
        maxAge: FiniteDuration
    )
  }

  case class Sponge(
      logo: String,
      service: String,
      sponsors: Seq[Logo]
  )

  case class Auth(
      api: Auth.Api,
      sso: Auth.Sso
  )
  object Auth {
    case class Api(
        url: String,
        avatarUrl: String,
        key: String,
        timeout: FiniteDuration,
        breaker: Api.Breaker
    )
    object Api {
      case class Breaker(
          maxFailures: Int,
          reset: FiniteDuration,
          timeout: FiniteDuration
      )
    }

    case class Sso(
        loginUrl: String,
        signupUrl: String,
        verifyUrl: String,
        secret: String,
        timeout: FiniteDuration,
        reset: FiniteDuration,
        apikey: String
    )
  }

  case class Mail(
      username: String,
      email: String,
      password: String,
      smtp: Mail.Smtp,
      transport: Mail.Transport,
      interval: FiniteDuration,
      properties: Map[String, String]
  )
  object Mail {
    case class Smtp(
        host: String,
        port: Int
    )

    case class Transport(
        protocol: String
    )
  }

  case class Performance(
      nioBlockingFibers: Int
  )
}

case class Logo(name: String, image: String, link: String)
