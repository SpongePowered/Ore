package ore

import scala.annotation.unused
import scala.concurrent.duration._
import scala.util.matching.Regex

import ore.db.DbRef
import ore.models.user.User
import ore.util.StringUtils._

import cats.data.NonEmptyList
import com.typesafe.config.ConfigMemorySize
import enumeratum.EnumEntry.Snakecase
import enumeratum._
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure, FailureReason}
import pureconfig.generic.semiauto._

case class OreConfig(
    application: OreConfig.App,
    ore: OreConfig.Ore,
    sponge: OreConfig.Sponge,
    auth: OreConfig.Auth,
    mail: OreConfig.Mail,
    performance: OreConfig.Performance
) {

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

  //SOE in compiler for some reason sometimes, so we keep this here. Seems to tame it
  implicit val reader: ConfigReader[OreConfig] = {
    import pureconfig.generic.auto._
    deriveReader[OreConfig]
  }

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
      session: Ore.Session,
      platforms: Seq[Ore.Platform],
      loaders: Map[String, Ore.Loader]
  ) {

    lazy val platformsByName: Map[String, Ore.Platform] =
      platforms.groupMapReduce(_.name)(identity)((_, snd) => snd)

    lazy val platformsByDependencyId: Map[String, Ore.Platform] =
      platforms.groupMapReduce(_.dependencyId)(identity)((_, snd) => snd)
  }
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
        unsafeDownloadMaxAge: FiniteDuration,
        uploadMaxSize: ConfigMemorySize
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

    case class Platform(
        name: String,
        category: String,
        categoryPriority: Int,
        dependencyId: String,
        url: String,
        coarseVersionRegex: Option[Regex] = None,
        noVersionPolicy: Platform.NoVersionPolicy = Platform.NoVersionPolicy.NotAllowed
    )
    object Platform {
      sealed trait NoVersionPolicy
      object NoVersionPolicy {
        case object NotAllowed extends NoVersionPolicy
        case object Warning    extends NoVersionPolicy
        case object Allowed    extends NoVersionPolicy
      }
    }

    import shapeless.tag.@@
    case class Loader(
        filename: NonEmptyList[String],
        dataType: Loader.DataType,
        rootType: Loader.RootType = Loader.RootType.AlwaysObject,
        entryLocation: Loader.EntryLocation = Loader.EntryLocation.Root,
        nameField: Loader.Field,
        identifierField: Loader.Field,
        versionField: Loader.Field,
        dependencyTypes: List[Loader.DependencyBlock] @@ Loader.NonDeterministic =
          shapeless.tag[Loader.NonDeterministic](List.empty[Loader.DependencyBlock])
    )
    object Loader {
      case object EmptyListError extends FailureReason {
        override def description: String = "Found empty list where non empty list is needed"
      }

      implicit def nelReader[A](implicit listReader: ConfigReader[List[A]]): ConfigReader[NonEmptyList[A]] =
        listReader.emap(xs => NonEmptyList.fromList(xs).toRight(EmptyListError))

      implicit val loaderConfigLoader: ConfigReader[OreConfig.Ore.Loader] = {
        @unused //Scalac is lying
        implicit val depdendencyTypesLoader: ConfigReader[List[Loader.DependencyBlock] @@ Loader.NonDeterministic] =
          ConfigReader[List[Loader.DependencyBlock]]
            .asInstanceOf[ConfigReader[List[Loader.DependencyBlock] @@ Loader.NonDeterministic]]

        deriveReader[OreConfig.Ore.Loader]
      }

      sealed trait NonDeterministic
      type Field = Seq[NonEmptyList[String]] @@ NonDeterministic
      object Field {
        //noinspection TypeAnnotation
        val empty = shapeless.tag[NonDeterministic].apply(Nil)
      }

      implicit val fieldConfigReader: ConfigReader[Field] =
        ConfigReader[Seq[NonEmptyList[String]]].map { xxs =>
          val res = shapeless.tag[NonDeterministic](xxs)
          res
        }

      sealed trait DataType extends EnumEntry with Snakecase
      object DataType extends Enum[DataType] {
        override def values: IndexedSeq[DataType] = findValues

        case object JSON     extends DataType
        case object YAML     extends DataType
        case object Manifest extends DataType
        case object TOML     extends DataType

        implicit val dataTypeConfigLoader: ConfigReader[DataType] = ConfigReader.fromStringOpt(withNameOption)
      }

      sealed trait RootType extends EnumEntry with Snakecase
      object RootType extends Enum[RootType] {
        override def values: IndexedSeq[RootType] = findValues

        case object AlwaysObject extends RootType
        case object ObjectOrList extends RootType
        case object AlwaysList   extends RootType

        implicit val dataTypeConfigLoader: ConfigReader[RootType] = ConfigReader.fromStringOpt(withNameOption)
      }

      sealed trait EntryLocation
      object EntryLocation {
        case object Root                      extends EntryLocation
        case class Field(field: Loader.Field) extends EntryLocation

        implicit val entryLocationConfigLoader: ConfigReader[EntryLocation] = ConfigReader.fromCursor { cursor =>
          for {
            objCursor    <- cursor.asObjectCursor
            atTypeCursor <- objCursor.atKey("type")
            tpe          <- atTypeCursor.asString
            res <- tpe match {
              case "root"  => Right(Root)
              case "field" => deriveReader[Field].from(cursor)
              case _ =>
                Left(
                  ConfigReaderFailures(
                    ConvertFailure(CannotConvert(tpe, "EntryLocation", "not a valid entry location type"), atTypeCursor)
                  )
                )
            }
          } yield res
        }
      }

      case class DependencyBlock(
          field: Field,
          dependencySyntax: DependencyBlock.DependencySyntax,
          versionSyntax: DependencyBlock.VersionSyntax,
          defaultIsRequired: Boolean = false
      )
      object DependencyBlock {
        implicit val reader: ConfigReader[DependencyBlock] = deriveReader[DependencyBlock]

        sealed trait DependencySyntax extends EnumEntry
        object DependencySyntax extends Enum[DependencySyntax] {
          override def values: IndexedSeq[DependencySyntax] = findValues

          case object AtSeparated extends DependencySyntax
          case class AsObject(
              identifierField: NonEmptyList[NonEmptyList[String]] @@ NonDeterministic,
              versionField: Field,
              requiredField: Field = Field.empty,
              optionalField: Field = Field.empty
          ) extends DependencySyntax

          implicit val dependencySyntaxConfigLoader: ConfigReader[DependencySyntax] =
            ConfigReader.fromCursor { cursor =>
              @unused //Scalac is lying
              implicit val nelNelStringConfigReader
                  : ConfigReader[NonEmptyList[NonEmptyList[String]] @@ NonDeterministic] =
                ConfigReader[NonEmptyList[NonEmptyList[String]]].map { xxs =>
                  val res = shapeless.tag[NonDeterministic](xxs)
                  res
                }

              for {
                objCursor    <- cursor.asObjectCursor
                atTypeCursor <- objCursor.atKey("type")
                tpe          <- atTypeCursor.asString
                res <- tpe match {
                  case "at-separated" => Right(AtSeparated)
                  case "as-object"    => deriveReader[AsObject].from(cursor)
                  case _ =>
                    Left(
                      ConfigReaderFailures(
                        ConvertFailure(
                          CannotConvert(tpe, "DependencySyntax", "not a valid dependency syntax type"),
                          atTypeCursor
                        )
                      )
                    )
                }
              } yield res
            }
        }

        sealed trait VersionSyntax extends EnumEntry with EnumEntry.Snakecase
        object VersionSyntax extends Enum[VersionSyntax] with Snakecase {
          override def values: IndexedSeq[VersionSyntax] = findValues

          case object Maven extends VersionSyntax

          implicit val versionSyntaxConfigLoader: ConfigReader[VersionSyntax] =
            ConfigReader.fromStringOpt(withNameOption)
        }
      }
    }
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
