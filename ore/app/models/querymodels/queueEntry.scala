package models.querymodels
import java.time.OffsetDateTime

import ore.data.project.ProjectNamespace
import ore.db.DbRef
import ore.models.user.User

case class UnsortedQueueEntry(
    namespace: ProjectNamespace,
    projectName: String,
    versionString: String,
    versionCreatedAt: OffsetDateTime,
    versionAuthor: Option[String],
    reviewerId: Option[DbRef[User]],
    reviewerName: Option[String],
    reviewStarted: Option[OffsetDateTime],
    reviewEnded: Option[OffsetDateTime]
) {

  def sort: Either[ReviewedQueueEntry, NotStartedQueueEntry] =
    if (reviewerId.isDefined) {
      Left(
        ReviewedQueueEntry(
          namespace,
          projectName,
          versionString,
          versionCreatedAt,
          versionAuthor,
          reviewerId.get,
          reviewerName.get,
          reviewStarted.get,
          reviewEnded
        )
      )
    } else {
      Right(
        NotStartedQueueEntry(
          namespace,
          projectName,
          versionString,
          versionCreatedAt,
          versionAuthor
        )
      )
    }
}

case class ReviewedQueueEntry(
    namespace: ProjectNamespace,
    projectName: String,
    versionString: String,
    versionCreatedAt: OffsetDateTime,
    versionAuthor: Option[String],
    reviewerId: DbRef[User],
    reviewerName: String,
    reviewStarted: OffsetDateTime,
    reviewEnded: Option[OffsetDateTime]
) {

  def isUnfinished: Boolean = reviewEnded.nonEmpty
}

case class NotStartedQueueEntry(
    namespace: ProjectNamespace,
    projectName: String,
    versionString: String,
    versionCreatedAt: OffsetDateTime,
    versionAuthor: Option[String]
)
