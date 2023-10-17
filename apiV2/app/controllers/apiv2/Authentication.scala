package controllers.apiv2

import java.time.OffsetDateTime
import java.util.UUID

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration

import play.api.http.HttpErrorHandler
import play.api.inject.ApplicationLifecycle
import play.api.mvc.{Codec => _, _}

import controllers.OreControllerComponents
import controllers.apiv2.helpers.{APIScope, ApiError}
import db.impl.query.apiv2.AuthQueries
import models.protocols.APIV2
import ore.db.impl.OrePostgresDriver.api._
import ore.models.api.ApiSession
import ore.models.user.FakeUser
import ore.permission.Permission

import akka.http.scaladsl.model.headers.HttpCredentials
import enumeratum.{Enum, EnumEntry}
import io.circe._
import io.circe.derivation.annotations.SnakeCaseJsonCodec
import zio.{IO, ZIO}

class Authentication(
    val errorHandler: HttpErrorHandler,
    fakeUser: FakeUser,
    lifecycle: ApplicationLifecycle
)(
    implicit oreComponents: OreControllerComponents
) extends AbstractApiV2Controller(lifecycle) {
  import Authentication._

  private def expiration(duration: FiniteDuration, userChoice: Option[Long]) = {
    val durationSeconds = duration.toSeconds

    userChoice
      .fold[Option[Long]](Some(durationSeconds))(d => if (d > durationSeconds) None else Some(d))
      .map(OffsetDateTime.now().plusSeconds)
  }

  def authenticateUser(): Action[AnyContent] = Authenticated.asyncF { implicit request =>
    val sessionExpiration = expiration(config.ore.api.session.expiration, None).get //Safe because user choice is None
    val uuidToken         = UUID.randomUUID().toString
    val sessionToInsert   = ApiSession(uuidToken, None, Some(request.user.id), sessionExpiration)

    service.insert(sessionToInsert).map { key =>
      Ok(
        ReturnedApiSession(
          key.token,
          key.expires,
          SessionType.User
        )
      )
    }
  }

  private val uuidRegex = """[0-9a-fA-F]{8}\-[0-9a-fA-F]{4}\-[0-9a-fA-F]{4}\-[0-9a-fA-F]{4}\-[0-9a-fA-F]{12}"""
  private val ApiKeyRegex =
    s"""($uuidRegex).($uuidRegex)""".r

  def authenticateKeyPublic(implicit request: Request[ApiSessionProperties]): ZIO[Any, Result, Result] = {
    lazy val sessionExpiration       = expiration(config.ore.api.session.expiration, request.body.expiresIn)
    lazy val publicSessionExpiration = expiration(config.ore.api.session.publicExpiration, request.body.expiresIn)

    def expirationToZIO(expiration: Option[OffsetDateTime]) =
      ZIO
        .fromOption(expiration)
        .orElseFail(BadRequest(ApiError("The requested expiration can't be used")))

    def unAuth(msg: String) = Unauthorized(ApiError(msg)).withHeaders(WWW_AUTHENTICATE -> "OreApi")

    val uuidToken = UUID.randomUUID().toString

    val findApiKey = ZIO.accessM[HttpCredentials] { creds =>
      creds.params.get("apikey") match {
        case Some(ApiKeyRegex(identifier, token)) => ZIO.succeed(AuthQueries.findApiKey(identifier, token).option)
        case _                                    => ZIO.fail(unAuth("No or invalid apikey parameter found in Authorization"))
      }
    }

    val validateCreds = for {
      expiration <- expirationToZIO(sessionExpiration)
      findQuery  <- findApiKey
      t          <- service.runDbCon(findQuery).get.orElseFail(unAuth("Invalid api key"))
      (keyId, keyOwnerId) = t
    } yield SessionType.Key -> ApiSession(uuidToken, Some(keyId), Some(keyOwnerId), expiration)

    val parsed = parseAuthHeader(request)
      .map(Right.apply)
      .catchAll {
        case ParseAuthHeaderError.NoAuthHeader =>
          expirationToZIO(publicSessionExpiration).map { expiration =>
            Left(SessionType.Public -> ApiSession(uuidToken, None, None, expiration))
          }
        case e => ZIO.fail(e.toResult)
      }

    //Only validate the credentials if they are present
    val sessionToInsert = parsed >>> (ZIO.identity[(Authentication.SessionType, ApiSession)] ||| validateCreds)

    for {
      t <- sessionToInsert
      (sessionType, session) = t
      _ <- service.insert(session)
    } yield Ok(
      ReturnedApiSession(
        session.token,
        session.expires,
        sessionType
      )
    )
  }

  def authenticateDev: ZIO[Any, Result, Result] = {
    if (fakeUser.isEnabled) {
      config.checkDebug()

      val sessionExpiration = expiration(config.ore.api.session.expiration, None).get //Safe because userChoice is None
      val uuidToken         = UUID.randomUUID().toString
      val sessionToInsert   = ApiSession(uuidToken, None, Some(fakeUser.id), sessionExpiration)

      service.insert(sessionToInsert).map { key =>
        Ok(
          ReturnedApiSession(
            key.token,
            key.expires,
            SessionType.Dev
          )
        )
      }
    } else {
      IO.fail(Forbidden)
    }
  }

  def defaultBody[A](parser: BodyParser[A], default: => A): BodyParser[A] = parse.using { request =>
    if (request.hasBody) parser
    else parse.ignore(default)
  }

  def authenticate(): Action[ApiSessionProperties] =
    Action.asyncF(defaultBody(parseCirce.decodeJson[ApiSessionProperties], ApiSessionProperties(None, None))) {
      implicit request => if (request.body._fake.getOrElse(false)) authenticateDev else authenticateKeyPublic
    }

  def deleteSession(): Action[AnyContent] = ApiAction(Permission.None, APIScope.GlobalScope).asyncF { request =>
    ZIO
      .fromOption(request.apiInfo.session)
      .orElseFail(BadRequest(ApiError("This request was not made with a session")))
      .flatMap(session => service.deleteWhere(ApiSession)(_.token === session))
      .as(NoContent)
  }
}
object Authentication {

  sealed abstract class SessionType extends EnumEntry with EnumEntry.Snakecase
  object SessionType extends Enum[SessionType] {
    case object Key    extends SessionType
    case object User   extends SessionType
    case object Public extends SessionType
    case object Dev    extends SessionType

    val values: immutable.IndexedSeq[SessionType] = findValues

    implicit val codec: Codec[SessionType] = APIV2.enumCodec(SessionType)(_.entryName)
  }

  @SnakeCaseJsonCodec case class ApiSessionProperties(
      _fake: Option[Boolean],
      expiresIn: Option[Long]
  )

  @SnakeCaseJsonCodec case class ReturnedApiSession(
      session: String,
      expires: OffsetDateTime,
      `type`: SessionType
  )
}
