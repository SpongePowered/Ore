package controllers.sugar

import scala.concurrent.{ExecutionContext, Future}

import play.api.http.{ContentTypes, HttpErrorHandler, Writeable}
import play.api.libs.{json => playjson}
import play.api.mvc.{BaseControllerHelpers, BodyParser, RequestHeader, Result}

import akka.util.ByteString
import cats.syntax.all._
import io.circe._
import io.circe.syntax._

trait CircePlayController { self: BaseControllerHelpers =>

  def errorHandler: HttpErrorHandler

  implicit val jsonWriteable: Writeable[Json] = Writeable(js => ByteString(js.noSpaces), Some(ContentTypes.JSON))

  implicit def asJsonWriteable[A: Encoder]: Writeable[A] =
    Writeable(obj => ByteString(obj.asJson.noSpaces), Some(ContentTypes.JSON))

  object parseCirce {

    private def convertPlayJson(js: playjson.JsValue): Json = js match {
      case playjson.JsString(value) => value.asJson
      case playjson.JsBoolean(bool) => bool.asJson
      case playjson.JsNumber(value) => value.asJson
      case playjson.JsNull          => Json.Null
      case playjson.JsArray(value)  => Json.arr(value.view.map(convertPlayJson).to(Seq): _*)
      case playjson.JsObject(underlying) =>
        Json.obj(underlying.view.map { case (k, v) => k -> convertPlayJson(v) }.to(Seq): _*)
      case playjson.JsTrue  => true.asJson
      case playjson.JsFalse => false.asJson
    }

    //Easiest way to do stuff
    def json(implicit ec: ExecutionContext): BodyParser[Json] =
      BodyParser("circe json")(parse.json(_).map(_.map(convertPlayJson)))

    def decodeJson[A: Decoder](implicit ec: ExecutionContext): BodyParser[A] =
      BodyParser("circe json decoder") { request =>
        json.apply(request).mapFuture {
          case Left(value) => Future.successful(Left(value))
          case Right(value) =>
            value.as[A].fold(e => createBadResult(e.show)(request).map(Left.apply), a => Future.successful(Right(a)))
        }
      }

    protected def createBadResult(msg: String, statusCode: Int = BAD_REQUEST): RequestHeader => Future[Result] =
      request => errorHandler.onClientError(request, statusCode, msg)
  }
}
