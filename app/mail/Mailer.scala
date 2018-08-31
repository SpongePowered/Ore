package mail

import java.security.Security
import java.util.Date

import akka.actor.{ActorSystem, Scheduler}
import com.sun.net.ssl.internal.ssl.Provider
import javax.inject.{Inject, Singleton}
import javax.mail.Message.RecipientType
import javax.mail.Session
import javax.mail.internet.{InternetAddress, MimeMessage}
import play.api.Configuration

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Handles dispatch of emails to users. Particularly for email verification.
  */
trait Mailer extends Runnable {

  /** The sender username */
  val username: String
  /** The sender email */
  val email: InternetAddress
  /** The sender password */
  val password: String

  /** SMTP server URL */
  val smtpHost: String
  /** SMTP port number */
  val smtpPort: Int = 465
  /** SMTP transport protocol */
  val transportProtocol: String = "smtps"

  /** The rate at which to send emails */
  val interval: FiniteDuration = 30.seconds
  val scheduler: Scheduler

  /** The properties to be applied to the [[Session]] */
  val properties: Map[String, Any] = Map.empty
  /** Pending emails */
  var queue: Seq[Email] = Seq.empty

  var suppressLogger = false
  val Logger = play.api.Logger("Mailer")

  private var session: Session = _

  private def log(msg: String): Unit = if (!this.suppressLogger) Logger.debug(msg)

  /**
    * Configures, initializes, and starts this Mailer.
    */
  def start()(implicit ec: ExecutionContext): Unit = {
    Security.addProvider(new Provider)
    val props = System.getProperties
    for (prop <- this.properties.keys)
      props.setProperty(prop, this.properties(prop).toString)
    this.session = Session.getInstance(props)
    this.scheduler.schedule(this.interval, this.interval, this)
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
  def push(email: Email): Unit = this.queue :+= email

  /**
    * Sends all queued [[Email]]s.
    */
  def run(): Unit = {
    if (queue.nonEmpty) {
      log(s"Sending ${this.queue.size} queued emails...")
      this.queue.foreach(send)
      this.queue = Seq.empty
      log("Done.")
    }
  }

}

@Singleton
final class SpongeMailer @Inject()(config: Configuration, actorSystem: ActorSystem)(implicit ec: ExecutionContext) extends Mailer {

  private val conf = config.get[Configuration]("mail")

  override val username: String = this.conf.get[String]("username")
  override val email: InternetAddress = InternetAddress.parse(this.conf.get[String]("email"))(0)
  override val password: String = this.conf.get[String]("password")
  override val smtpHost: String = this.conf.get[String]("smtp.host")
  override val smtpPort: Int = this.conf.get[Int]("smtp.port")
  override val transportProtocol: String = this.conf.get[String]("transport.protocol")
  override val interval: FiniteDuration = this.conf.get[FiniteDuration]("interval")
  override val scheduler: Scheduler = this.actorSystem.scheduler
  override val properties: Map[String, String] = this.conf.get[Map[String, String]]("properties")

  start()

}
