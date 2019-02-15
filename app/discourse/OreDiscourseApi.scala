package discourse

import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import db.{DbModel, ModelService}
import models.project.{Project, Version}
import models.user.User
import ore.OreConfig
import util.StringUtils._
import util.syntax._

import akka.actor.Scheduler
import cats.data.EitherT
import cats.effect.{ContextShift, IO, Timer}
import cats.syntax.all._
import com.google.common.base.Preconditions.checkArgument
import com.typesafe.scalalogging
import org.spongepowered.play.discourse.DiscourseApi
import org.spongepowered.play.discourse.model.DiscoursePost

/**
  * An implementation of [[DiscourseApi]] suited to Ore's needs.
  *
  * Note: It is very important that the implementor of this trait is a
  * singleton, otherwise countless threads will be spawned from this object's
  * [[RecoveryTask]].
  */
abstract class OreDiscourseApi(implicit cs: ContextShift[IO], timer: Timer[IO]) extends DiscourseApiF[IO] {

  def isEnabled: Boolean

  /** The category where project topics are posted to */
  def categoryDefault: Int

  /** The category where deleted project topics are moved to */
  def categoryDeleted: Int

  /** Path to project topic template */
  def topicTemplatePath: Path

  /** Path to version release template */
  def versionReleasePostTemplatePath: Path

  /** Rate at which to retry failed attempts */
  def retryRate: FiniteDuration

  /** Scheduler for maintaining synchronization when requests fail */
  def scheduler: Scheduler

  /** The base URL for this instance */
  def baseUrl: String

  private val MDCLogger = scalalogging.Logger.takingImplicit[DiscourseMDC](Logger.logger)

  /**
    * Initializes and starts this API instance.
    */
  def start(implicit ec: ExecutionContext, service: ModelService, config: OreConfig): Unit = {
    if (!this.isEnabled) {
      Logger.info("Discourse API initialized in disabled mode.")
    } else {
      new RecoveryTask(this.scheduler, this.retryRate, this).start()
      Logger.info("Discourse API initialized.")
    }
  }

  /**
    * Creates a new topic for the specified [[Project]].
    *
    * @param project Project to create topic for.
    * @return        True if successful
    */
  def createProjectTopic(
      project: DbModel[Project]
  )(implicit service: ModelService, config: OreConfig): IO[DbModel[Project]] = {
    if (!this.isEnabled)
      IO.pure(project)
    else {
      val title = Templates.projectTitle(project)

      implicit val mdc: DiscourseMDC = DiscourseMDC(project.ownerName, None, title)

      val createTopicProgram = (content: String) =>
        createTopicF(poster = project.ownerName, title = title, content = content, categoryId = Some(categoryDefault))

      def sanityCheck(check: Boolean, msg: => String) = if (!check) IO.raiseError(new Exception(msg)) else IO.unit

      val res = for {
        content <- EitherT.right[(List[String], String)](Templates.projectTopic(project))
        topic   <- createTopicProgram(content).leftMap((_, content))
        // Topic created!
        // Catch some unexpected cases (should never happen)
        _ <- EitherT.right[(List[String], String)](sanityCheck(topic.isTopic, "project post isn't topic?"))
        _ <- EitherT.right[(List[String], String)](
          sanityCheck(topic.username == project.ownerName, "project post user isn't owner?")
        )
        _ = MDCLogger.debug(s"""|New project topic:
                                |Project: ${project.url}
                                |Topic ID: ${topic.topicId}
                                |Post ID: ${topic.postId}""".stripMargin)
        project <- EitherT.right[(List[String], String)](
          service.update(project)(_.copy(topicId = Some(topic.topicId), postId = Some(topic.postId)))
        )
      } yield project

      res
        .leftSemiflatMap {
          case (errors, content) =>
            // Request went through but Discourse responded with errors
            // Don't schedule a retry because this will just keep happening
            val message =
              s"""|Request to create project topic was successful but Discourse responded with errors:
                  |Project: ${project.url}
                  |Title: $title
                  |Content: $content
                  |Errors: ${errors.mkString(", ")}""".stripMargin
            MDCLogger.warn(message)
            project.logger.flatMap(_.err(message)).as(project)
        }
        .merge
        .onError {
          case e =>
            IO(MDCLogger.warn(s"Could not create project topic for project ${project.url}. Rescheduling...", e))
        }
    }
  }

  /**
    * Updates a [[Project]]'s forum topic with the appropriate content.
    *
    * @param project  Project to update topic for
    * @return         True if successful
    */
  def updateProjectTopic(
      project: DbModel[Project]
  )(implicit service: ModelService, config: OreConfig): IO[Boolean] = {
    if (!this.isEnabled)
      IO.pure(true)
    else {
      checkArgument(project.topicId.isDefined, "undefined topic id", "")
      checkArgument(project.postId.isDefined, "undefined post id", "")

      val topicId   = project.topicId
      val postId    = project.postId
      val title     = Templates.projectTitle(project)
      val ownerName = project.ownerName

      implicit val mdc: DiscourseMDC = DiscourseMDC(ownerName, topicId, title)

      def logErrorsAs(errors: List[String], as: Boolean): IO[Boolean] = {
        val message =
          s"""|Request to update project topic was successful but Discourse responded with errors:
              |Project: ${project.url}
              |Topic ID: $topicId
              |Title: $title
              |Errors: ${errors.toString}""".stripMargin
        MDCLogger.warn(message)
        project.logger.flatMap(_.err(message)).as(as)
      }

      val updateTopicProgram =
        updateTopicF(username = ownerName, topicId = topicId.get, title = Some(title), categoryId = None)

      val updatePostProgram = (content: String) =>
        updatePostF(username = ownerName, postId = postId.get, content = content)

      val res = for {
        // Set flag so that if we are interrupted we will remember to do it later
        _       <- EitherT.right[Boolean](service.update(project)(_.copy(isTopicDirty = true)))
        content <- EitherT.right[Boolean](Templates.projectTopic(project))
        _       <- updateTopicProgram.leftSemiflatMap(logErrorsAs(_, as = false))
        _       <- updatePostProgram(content).leftSemiflatMap(logErrorsAs(_, as = false))
        _ = MDCLogger.debug(s"Project topic updated for ${project.url}.")
        _ <- EitherT.right[Boolean](service.update(project)(_.copy(isTopicDirty = false)))
      } yield true

      res.merge
    }
  }

  /**
    * Posts a new reply to a [[Project]]'s forum topic.
    *
    * @param project  Project to post to
    * @param user     User who is posting
    * @param content  Post content
    * @return         List of errors Discourse returns
    */
  def postDiscussionReply(project: Project, user: User, content: String): EitherT[IO, List[String], DiscoursePost] = {
    if (!this.isEnabled) {
      Logger.warn("Tried to post discussion with API disabled?") // Shouldn't be reachable
      EitherT.leftT[IO, DiscoursePost](Nil: List[String])
    } else {
      checkArgument(project.topicId.isDefined, "undefined topic id", "")
      EitherT(
        createPostF(username = user.name, topicId = project.topicId.get, content = content).value
          .orElse(IO.pure(Left(List("Could not connect to forums, please try again later."))))
      )
    }
  }

  /**
    * Posts a new "Version release" to a [[Project]]'s forum topic.
    *
    * @param project Project to post release to
    * @param version Version of project
    * @return
    */
  def postVersionRelease(project: DbModel[Project], version: Version, content: Option[String])(
      implicit service: ModelService,
      cs: ContextShift[IO]
  ): EitherT[IO, List[String], DiscoursePost] = {
    import cats.instances.list._
    if (!this.isEnabled)
      EitherT.leftT[IO, DiscoursePost](Nil: List[String])
    else {
      checkArgument(version.projectId == project.id.value, "invalid version project pair", "")
      EitherT.liftF(project.owner.user).flatMap { user =>
        postDiscussionReply(project, user, content = Templates.versionRelease(project, version, content))
          .leftSemiflatMap(errors => project.logger.flatMap(logger => errors.parTraverse(logger.err)).as(errors))
      }
    }
  }

  def changeTopicVisibility(project: Project, isVisible: Boolean): IO[Unit] = {
    if (!this.isEnabled)
      IO.pure(())
    else {
      checkArgument(project.topicId.isDefined, "undefined topic id", "")

      updateTopicF(admin, project.topicId.get, None, Some(if (isVisible) categoryDefault else categoryDeleted))
        .fold(
          errors =>
            IO.raiseError(
              new Exception(s"Couldn't hide topic for project: ${project.url}. Message: " + errors.mkString(" | "))
          ),
          _ => IO.unit
        )
        .flatten
    }
  }

  /**
    * Delete's a [[Project]]'s forum topic.
    *
    * @param project  Project to delete topic for
    * @return         True if deleted
    */
  def deleteProjectTopic(project: DbModel[Project])(implicit service: ModelService): IO[DbModel[Project]] = {
    if (!this.isEnabled)
      IO.pure(project)
    else {
      checkArgument(project.topicId.isDefined, "undefined topic id", "")

      def logFailure(): Unit = Logger.warn(s"Couldn't delete topic for project: ${project.url}. Rescheduling...")

      val deleteForums = deleteTopicF(admin, project.topicId.get).onError { case _ => IO(logFailure()) }
      deleteForums *> service.update(project)(_.copy(topicId = None, postId = None))
    }
  }

  /**
    * Returns a future result of the amount of users on Discourse that are in
    * this list.
    *
    * @param users  Users to check
    * @return       Amount on discourse
    */
  def countUsers(users: List[String])(implicit cs: ContextShift[IO]): IO[Int] = {
    import cats.instances.list._
    if (!this.isEnabled)
      IO.pure(0)
    else users.parTraverse(u => userExistsF(u).orElse(IO.pure(false))).map(_.count(_ == true))
  }

  /**
    * Discourse content templates.
    */
  object Templates {

    /** Creates a new title for a project topic. */
    def projectTitle(project: Project): String = project.name + project.description.fold("")(d => s" - $d")

    /** Generates the content for a project topic. */
    def projectTopic(
        project: DbModel[Project]
    )(implicit config: OreConfig, service: ModelService): IO[String] = project.homePage.map { page =>
      readAndFormatFile(
        topicTemplatePath,
        project.name,
        baseUrl + '/' + project.url,
        page.contents
      )
    }

    /** Generates the content for a version release post. */
    def versionRelease(project: Project, version: Version, content: Option[String]): String = {
      implicit val p: Project = project
      readAndFormatFile(
        versionReleasePostTemplatePath,
        project.name,
        baseUrl + '/' + project.url,
        baseUrl + '/' + version.url,
        content.getOrElse("*No description given.*")
      )
    }

  }

}
