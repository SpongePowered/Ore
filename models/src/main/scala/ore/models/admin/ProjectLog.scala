package ore.models.admin

import scala.language.higherKinds

import java.time.Instant

import ore.db.impl.DefaultModelCompanion
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{ProjectLogEntryTable, ProjectLogTable}
import ore.models.project.Project
import ore.db.access.{ModelView, QueryView}
import ore.db.{DbRef, Model, ModelQuery, ModelService}
import ore.syntax._

import cats.Monad

/**
  * Represents a log for a [[ore.models.project.Project]].
  *
  * @param projectId  ID of project log is for
  */
case class ProjectLog(
    projectId: DbRef[Project]
)
object ProjectLog extends DefaultModelCompanion[ProjectLog, ProjectLogTable](TableQuery[ProjectLogTable]) {

  implicit val query: ModelQuery[ProjectLog] =
    ModelQuery.from(this)

  implicit class ProjectLogModelOps(private val self: Model[ProjectLog]) extends AnyVal {

    /**
      * Returns all entries in this log.
      *
      * @return Entries in log
      */
    def entries[V[_, _]: QueryView](
        view: V[ProjectLogEntryTable, Model[ProjectLogEntry]]
    ): V[ProjectLogEntryTable, Model[ProjectLogEntry]] =
      view.filterView(_.logId === self.id.value)

    /**
      * Adds a new entry with an "error" tag to the log.
      *
      * @param message  Message to log
      * @return         New entry
      */
    def err[F[_]: Monad](message: String)(implicit service: ModelService[F]): F[Model[ProjectLogEntry]] = {
      val tag = "error"
      entries(ModelView.now(ProjectLogEntry))
        .find(e => e.message === message && e.tag === tag)
        .semiflatMap { entry =>
          service.update(entry)(
            _.copy(
              occurrences = entry.occurrences + 1,
              lastOccurrence = Instant.now()
            )
          )
        }
        .getOrElseF {
          service.insert(
            ProjectLogEntry(self.id, tag, message, lastOccurrence = Instant.now())
          )
        }
    }
  }
}
