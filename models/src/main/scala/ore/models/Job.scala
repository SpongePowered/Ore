package ore.models

import java.time.OffsetDateTime

import ore.db.impl.DefaultModelCompanion
import ore.db.impl.schema.JobTable
import ore.db.{DbRef, ModelQuery}
import ore.models.project.{Project, Version}

import enumeratum.values._
import slick.lifted.TableQuery

case class JobInfo(
    lastUpdated: Option[OffsetDateTime],
    retryAt: Option[OffsetDateTime],
    lastError: Option[String],
    lastErrorDescriptor: Option[String],
    state: Job.JobState,
    jobType: Job.JobType
) {

  def withoutError: JobInfo = copy(lastError = None)
}
object JobInfo {
  def newJob(tpe: Job.JobType): JobInfo = JobInfo(None, None, None, None, Job.JobState.NotStarted, tpe)
}

case class Job(
    info: JobInfo,
    jobProperties: Map[String, String]
) {

  def toTyped: Either[String, info.jobType.CaseClass] =
    info.jobType.toCaseClass(info, jobProperties)
}
object Job extends DefaultModelCompanion[Job, JobTable](TableQuery[JobTable]) {

  implicit val query: ModelQuery[Job] =
    ModelQuery.from(this)

  sealed abstract class JobState(val value: String) extends StringEnumEntry
  object JobState extends StringEnum[JobState] {
    override def values: IndexedSeq[JobState] = findValues

    case object NotStarted   extends JobState("not_started")
    case object Started      extends JobState("started")
    case object Done         extends JobState("done")
    case object FatalFailure extends JobState("fatal_failure")
  }

  sealed abstract class JobType(val value: String) extends StringEnumEntry {
    type CaseClass <: TypedJob

    def toCaseClass(
        info: JobInfo,
        properties: Map[String, String]
    ): Either[String, CaseClass]
  }
  object JobType extends StringEnum[JobType] {
    override def values: IndexedSeq[JobType] =
      IndexedSeq(UpdateDiscourseProjectTopic, UpdateDiscourseVersionPost, DeleteDiscourseTopic, PostDiscourseReply)
  }

  sealed trait TypedJob {
    def info: JobInfo

    def toJob: Job

    def withoutError: TypedJob
  }

  case class UpdateDiscourseProjectTopic(
      info: JobInfo,
      projectId: DbRef[Project]
  ) extends TypedJob {

    def toJob: Job =
      Job(info, Map("project_id" -> projectId.toString))

    override def withoutError: TypedJob = UpdateDiscourseProjectTopic(info.withoutError, projectId)
  }
  object UpdateDiscourseProjectTopic extends JobType("update_project_discourse_topic") {
    def newJob(projectId: DbRef[Project]): UpdateDiscourseProjectTopic =
      UpdateDiscourseProjectTopic(JobInfo.newJob(this), projectId)

    override type CaseClass = UpdateDiscourseProjectTopic

    def toCaseClass(
        info: JobInfo,
        properties: Map[String, String]
    ): Either[String, CaseClass] =
      properties
        .get("project_id")
        .toRight("No project id found")
        .flatMap(_.toLongOption.toRight("Project id is not a valid long"))
        .map(l => UpdateDiscourseProjectTopic(info, l))
  }

  case class UpdateDiscourseVersionPost(
      info: JobInfo,
      versionId: DbRef[Version]
  ) extends TypedJob {

    def toJob: Job =
      Job(info, Map("version_id" -> versionId.toString))

    override def withoutError: TypedJob = UpdateDiscourseVersionPost(info.withoutError, versionId)
  }
  object UpdateDiscourseVersionPost extends JobType("update_version_discourse_post") {
    def newJob(versionId: DbRef[Version]): UpdateDiscourseVersionPost =
      UpdateDiscourseVersionPost(JobInfo.newJob(this), versionId)

    override type CaseClass = UpdateDiscourseVersionPost

    def toCaseClass(
        info: JobInfo,
        properties: Map[String, String]
    ): Either[String, CaseClass] =
      properties
        .get("version_id")
        .toRight("No version id found")
        .flatMap(_.toLongOption.toRight("Version id is not a valid long"))
        .map(l => UpdateDiscourseVersionPost(info, l))
  }

  case class DeleteDiscourseTopic(
      info: JobInfo,
      topicId: Int
  ) extends TypedJob {

    def toJob: Job =
      Job(info, Map("topic_id" -> topicId.toString))

    override def withoutError: TypedJob = DeleteDiscourseTopic(info.withoutError, topicId)
  }
  object DeleteDiscourseTopic extends JobType("delete_discourse_topic") {
    def newJob(topicId: Int): DeleteDiscourseTopic =
      DeleteDiscourseTopic(JobInfo.newJob(this), topicId)

    override type CaseClass = DeleteDiscourseTopic

    def toCaseClass(
        info: JobInfo,
        properties: Map[String, String]
    ): Either[String, CaseClass] =
      properties
        .get("topic_id")
        .toRight("No topic id found")
        .flatMap(_.toIntOption.toRight("Topic id is not a valid long"))
        .map(l => DeleteDiscourseTopic(info, l))
  }

  case class PostDiscourseReply(info: JobInfo, topicId: Int, poster: String, content: String) extends TypedJob {
    override def toJob: Job = Job(info, Map("topic_id" -> topicId.toString, "poster" -> poster, "content" -> content))

    override def withoutError: TypedJob = PostDiscourseReply(info.withoutError, topicId, poster, content)
  }
  object PostDiscourseReply extends JobType("post_discourse_reply") {
    def newJob(topicId: Int, poster: String, content: String): PostDiscourseReply =
      PostDiscourseReply(JobInfo.newJob(this), topicId, poster, content)

    override type CaseClass = PostDiscourseReply

    override def toCaseClass(info: JobInfo, properties: Map[String, String]): Either[String, CaseClass] = {
      for {
        stringTopicId <- properties.get("topic_id").toRight("No topic id found")
        topicId       <- stringTopicId.toIntOption.toRight("Topic id is not a valid long")
        poster        <- properties.get("poster").toRight("No poster found")
        content       <- properties.get("content").toRight("No content found")
      } yield PostDiscourseReply(info, topicId, poster, content)
    }
  }
}
