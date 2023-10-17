package mail

import java.security.Security
import java.util.Date
import javax.mail.Message.RecipientType
import javax.mail.Session
import javax.mail.internet.{InternetAddress, MimeMessage}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import ore.OreConfig

import akka.actor.{ActorSystem, Scheduler}
import com.typesafe.scalalogging

/**
  * Handles dispatch of emails to users. Particularly for email verification.
  */
trait Mailer extends Runnable {

  /** The sender username */
  def username: String

  /** The sender email */
  def email: InternetAddress

  /** The sender password */
  def password: String

  /** SMTP server URL */
  def smtpHost: String

  /** SMTP port number */
  val smtpPort: Int = 465

  /** SMTP transport protocol */
  val transportProtocol: String = "smtps"

  /** The rate at which to send emails */
  val interval: FiniteDuration = 30.seconds
  def scheduler: Scheduler

  /** The properties to be applied to the [[Session]] */
  val properties: Map[String, Any] = Map.empty

  /** Pending emails */
  val queue: ArrayBuffer[Email] = ArrayBuffer.empty

  val suppressLogger = false
  private val Logger = scalalogging.Logger("Mailer")

  private var session: Session = _ // scalafix:ok

  private def log(msg: String): Unit = if (!this.suppressLogger) Logger.debug(msg)

  /**
    * Configures, initializes, and starts this Mailer.
    */
  def start()(implicit ec: ExecutionContext): Unit = {
    Security.addProvider(Security.getProvider("SunJSSE"))
    val props = System.getProperties
    for (prop <- this.properties.keys)
      props.setProperty(prop, this.properties(prop).toString)
    this.session = Session.getInstance(props)
    this.scheduler.scheduleWithFixedDelay(this.interval, this.interval)(this)
    log("Started")
  }

  /**
    * Sends the specified [[Email]].
    *
    * @param email Email to send
    */
  def send(email: Email): Unit = {
    log("Sending email to " + email.recipient + "...")
    val message = new MimeMessage(this.session)
    message.setFrom(this.email)
    message.setRecipients(RecipientType.TO, email.recipient)
    message.setSubject(email.subject)
    message.setContent(email.content.toString, "text/html")
    message.setSentDate(new Date())

    val transport = this.session.getTransport(this.transportProtocol)
    transport.connect(this.smtpHost, this.smtpPort, this.username, this.password)
    transport.sendMessage(message, message.getAllRecipients)
    transport.close()
  }

  /**
    * Pushes a new [[Email]] to the queue.
    *
    * @param email Email to push
    */
  def push(email: Email): Unit = this.queue += email

  /**
    * Sends all queued [[Email]]s.
    */
  def run(): Unit = {
    if (queue.nonEmpty) {
      log(s"Sending ${this.queue.size} queued emails...")
      this.queue.foreach(send)
      this.queue.clear()
      log("Done.")
    }
  }

}

final class SpongeMailer(config: OreConfig, actorSystem: ActorSystem)(implicit ec: ExecutionContext) extends Mailer {

  private val conf = config.mail

  override val username: String                = this.conf.username
  override val email: InternetAddress          = InternetAddress.parse(this.conf.email)(0)
  override val password: String                = this.conf.password
  override val smtpHost: String                = this.conf.smtp.host
  override val smtpPort: Int                   = this.conf.smtp.port
  override val transportProtocol: String       = this.conf.transport.protocol
  override val interval: FiniteDuration        = this.conf.interval
  override val scheduler: Scheduler            = this.actorSystem.scheduler
  override val properties: Map[String, String] = this.conf.properties

  start()

}
