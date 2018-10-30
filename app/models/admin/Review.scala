package models.admin

import java.sql.Timestamp

import scala.concurrent.{ExecutionContext, Future}

import play.api.i18n.Messages
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.twirl.api.Html

import db.impl.schema.ReviewTable
import db.{DbRef, Model, ModelQuery, ModelService, ObjId, ObjectTimestamp}
import models.project.{Page, Project, Version}
import models.user.User
import ore.OreConfig
import _root_.util.StringUtils

import slick.lifted.TableQuery

/**
  * Represents an approval instance of [[Project]] [[Version]].
  *
  * @param id           Unique ID
  * @param createdAt    When it was created
  * @param versionId    User who is approving
  * @param userId       User who is approving
  * @param endedAt      When the approval process ended
  * @param message      Message of why it ended
  */
case class Review(
    id: ObjId[Review] = ObjId.Uninitialized(),
    createdAt: ObjectTimestamp = ObjectTimestamp.Uninitialized,
    versionId: DbRef[Version],
    userId: DbRef[User],
    endedAt: Option[Timestamp],
    message: String
) extends Model {

  /** Self referential type */
  override type M = Review

  /** The model's table */
  override type T = ReviewTable

  /**
    * Add new message
    */
  def addMessage(message: Message)(implicit ec: ExecutionContext, service: ModelService): Future[Review] = {
    val messages = decodeMessages :+ message
    val js       = Json.toJson(messages)
    service.update(
      copy(
        message = Json.stringify(
          JsObject(
            Seq(
              "messages" -> js
            )
          )
        )
      )
    )
  }

  /**
    * Get all messages
    * @return
    */
  def decodeMessages: Seq[Message] = {
    if (message.startsWith("{") && message.endsWith("}")) {
      val messages: JsValue = Json.parse(message)
      (messages \ "messages").as[Seq[Message]]
    } else {
      Seq()
    }
  }
}

/**
  * This modal is needed to convert the json
  */
case class Message(message: String, time: Long = System.currentTimeMillis(), action: String = "message") {
  def getTime(implicit messages: Messages): String = StringUtils.prettifyDateAndTime(new Timestamp(time))
  def isTakeover: Boolean                          = action.equalsIgnoreCase("takeover")
  def isStop: Boolean                              = action.equalsIgnoreCase("stop")
  def render(implicit oreConfig: OreConfig): Html  = Page.render(message)
}
object Message {
  implicit val messageReads: Reads[Message] =
    (JsPath \ "message")
      .read[String]
      .and((JsPath \ "time").read[Long])
      .and((JsPath \ "action").read[String])(Message.apply _)

  implicit val messageWrites: Writes[Message] = (message: Message) =>
    Json.obj(
      "message" -> message.message,
      "time"    -> message.time,
      "action"  -> message.action
  )
}

object Review {
  def ordering: Ordering[(Review, _)] =
    // TODO make simple + check order
    Ordering.by(_._1.createdAt.value.getTime)

  def ordering2: Ordering[Review] =
    // TODO make simple + check order
    Ordering.by(_.createdAt.value.getTime)

  implicit val query: ModelQuery[Review] =
    ModelQuery.from[Review](TableQuery[ReviewTable], _.copy(_, _))
}
