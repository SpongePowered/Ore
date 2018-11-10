package models.admin

import java.sql.Timestamp

import scala.concurrent.{ExecutionContext, Future}

import play.api.i18n.Messages
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.twirl.api.Html

import db.impl.schema.{ReviewSchema, ReviewTable}
import db.{Model, ModelService, ObjectId, ObjectReference, ObjectTimestamp}
import models.project.{Page, Project, Version}
import ore.OreConfig
import _root_.util.StringUtils

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
    id: ObjectId = ObjectId.Uninitialized,
    createdAt: ObjectTimestamp = ObjectTimestamp.Uninitialized,
    versionId: ObjectReference,
    userId: ObjectReference,
    endedAt: Option[Timestamp],
    message: JsValue
) extends Model {

  /** Self referential type */
  override type M = Review

  /** The model's table */
  override type T = ReviewTable

  /** The model's schema */
  override type S = ReviewSchema

  /**
    * Add new message
    */
  def addMessage(message: Message)(implicit ec: ExecutionContext, service: ModelService): Future[Review] = {
    val messages = decodeMessages :+ message
    service.update(
      copy(
        message = JsObject(
          Seq("messages" -> Json.toJson(messages))
        )
      )
    )
  }

  /**
    * Get all messages
    * @return
    */
  def decodeMessages: Seq[Message] =
    (message \ "messages").as[Seq[Message]]

  /**
    * Returns a copy of this model with an updated ID and timestamp.
    *
    * @param id      ID to set
    * @param theTime Timestamp
    * @return Copy of model
    */
  override def copyWith(id: ObjectId, theTime: ObjectTimestamp): Model = this.copy(id = id, createdAt = createdAt)
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
}
