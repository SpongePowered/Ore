package ore.discourse

import java.text.MessageFormat

import ore.OreJobsConfig
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.{Model, ModelService}
import ore.models.project.{Page, Project, Version, Visibility}
import ore.syntax._

import cats.data.EitherT
import cats.effect.Effect
import cats.syntax.all._
import com.typesafe.scalalogging

/**
  * A base implementation of [[OreDiscourseApi]] that uses [[DiscourseApi]].
  *
  * @param categoryDefault The category where project topics are posted to
  * @param categoryDeleted The category where deleted project topics are moved to
  * @param topicTemplate Project topic template
  * @param versionReleasePostTemplate Version release template
  * @param admin An admin account to fall back to if no user is specified as poster
  */
class OreDiscourseApiEnabled[F[_]](
    api: DiscourseApi[F],
    categoryDefault: Int,
    categoryDeleted: Int,
    topicTemplate: String,
    versionReleasePostTemplate: String,
    admin: String
)(
    implicit service: ModelService[F],
    config: OreJobsConfig,
    F: Effect[F]
) extends OreDiscourseApi[F] {

  private val MDCLogger           = scalalogging.Logger.takingImplicit[DiscourseMDC]("Discourse")
  protected[discourse] val Logger = scalalogging.Logger(MDCLogger.underlying)

  private def homePage(project: Model[Project]): F[Model[Page]] =
    ModelView
      .now(Page)
      .find(p => p.projectId === project.id.value && p.name === config.ore.pages.home.name)
      .value
      .flatMap {
        case Some(value) => F.pure(value)
        case None        => F.raiseError(new Exception("No homepage found for project"))
      }

  /**
    * Creates a new topic for the specified [[Project]].
    *
    * @param project Project to create topic for.
    * @return        True if successful
    */
  def createProjectTopic(project: Model[Project]): F[Either[DiscourseError, Model[Project]]] = {
    val title = Templates.projectTitle(project)

    implicit val mdc: DiscourseMDC = DiscourseMDC(project.ownerName, None, title)

    val createTopicProgram = (content: String) =>
      api.createTopic(
        poster = project.ownerName,
        title = title,
        content = content,
        categoryId = Some(categoryDefault)
      )

    def sanityCheck(check: Boolean, msg: => String) = if (!check) F.raiseError[Unit](new Exception(msg)) else F.unit

    val res = for {
      homePage <- EitherT.right[DiscourseError](homePage(project))
      content = Templates.projectTopic(project, homePage.contents.getOrElse(sys.error("Homepage must have contents")))
      topic <- EitherT(createTopicProgram(content))
      // Topic created!
      // Catch some unexpected cases (should never happen)
      _ <- EitherT.right[DiscourseError](sanityCheck(topic.isTopic, "project post isn't topic?"))
      _ <- EitherT.right[DiscourseError](
        sanityCheck(topic.username == project.ownerName, "project post user isn't owner?")
      )
      _ = MDCLogger.debug(s"""|New project topic:
                              |Project: ${project.url}
                              |Topic ID: ${topic.topicId}
                              |Post ID: ${topic.postId}""".stripMargin)
      project <- EitherT.right[DiscourseError](
        service.update(project)(_.copy(topicId = Some(topic.topicId), postId = Some(topic.postId)))
      )
    } yield project

    res.value
  }

  def updateProjectTopic(project: Model[Project]): F[Either[DiscourseError, Unit]] = {
    require(project.topicId.isDefined, "undefined topic id")
    require(project.postId.isDefined, "undefined post id")

    val topicId   = project.topicId
    val postId    = project.postId
    val title     = Templates.projectTitle(project)
    val ownerName = project.ownerName

    implicit val mdc: DiscourseMDC = DiscourseMDC(ownerName, topicId, title)

    val updateTopicProgram =
      api.updateTopic(
        poster = ownerName,
        topicId = topicId.get,
        title = Some(title),
        categoryId = Some(if (Visibility.isPublic(project.visibility)) categoryDefault else categoryDeleted)
      )

    val updatePostProgram = (content: String) =>
      api.updatePost(poster = ownerName, postId = postId.get, content = content)

    val res = for {
      homePage <- EitherT.right[DiscourseError](homePage(project))
      content = Templates.projectTopic(project, homePage.contents.getOrElse(sys.error("Homepage must have contents")))
      _ <- EitherT(updateTopicProgram)
      _ <- EitherT(updatePostProgram(content))
      _ = MDCLogger.debug(s"Project topic updated for ${project.url}.")
    } yield ()

    res.value
  }

  def postDiscussionReply(topicId: Int, poster: String, content: String): F[Either[DiscourseError, DiscoursePost]] =
    api.createPost(poster = poster, topicId = topicId, content = content)

  def createVersionPost(project: Model[Project], version: Model[Version]): F[Either[DiscourseError, Model[Version]]] = {
    EitherT
      .liftF(project.user)
      .flatMapF { user =>
        postDiscussionReply(
          project.topicId.get,
          user.name,
          content = Templates.versionRelease(project, version, version.description)
        )
      }
      .semiflatMap(post => service.update(version)(_.copy(postId = Some(post.postId))))
      .value
  }

  def updateVersionPost(project: Model[Project], version: Model[Version]): F[Either[DiscourseError, Unit]] = {
    require(project.topicId.isDefined, "undefined topic id")
    require(version.postId.isDefined, "undefined post id")

    val postId    = version.postId
    val ownerName = project.ownerName
    val content   = Templates.versionRelease(project, version, version.description)

    api.updatePost(poster = ownerName, postId = postId.get, content = content)
  }

  def deleteTopic(topicId: Int): F[Either[DiscourseError, Unit]] =
    api.deleteTopic(admin, topicId)

  /**
    * Discourse content templates.
    */
  object Templates {

    /** Creates a new title for a project topic. */
    def projectTitle(project: Project): String = project.name + project.description.fold("")(d => s" - $d")

    /** Generates the content for a project topic. */
    def projectTopic(project: Model[Project], content: String): String = MessageFormat.format(
      topicTemplate,
      project.name,
      s"${config.ore.baseUrl}/${project.url}",
      content
    )

    /** Generates the content for a version release post. */
    def versionRelease(project: Project, version: Version, content: Option[String]): String = {
      MessageFormat.format(
        versionReleasePostTemplate,
        project.name,
        s"${config.ore.baseUrl}/${project.url}",
        s"${config.ore.baseUrl}/${version.url(project)}",
        content.getOrElse("*No description given.*")
      )
    }

  }
}
