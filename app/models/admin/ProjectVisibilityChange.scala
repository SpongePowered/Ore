package models.admin

import java.sql.Timestamp

import db.Model
import db.impl.model.OreModel
import db.impl.table.ModelKeys._
import models.project.Page
import models.user.User
import util.functional.OptionT
import util.instances.future._
import play.twirl.api.Html
import scala.concurrent.{ExecutionContext, Future}

import db.impl.ProjectVisibilityChangeTable
import db.impl.model.common.VisibilityChange

case class ProjectVisibilityChange(override val id: Option[Int] = None,
                            override val createdAt: Option[Timestamp] = None,
                            createdBy: Option[Int] = None,
                            projectId: Int = -1,
                            comment: String,
                            var resolvedAt: Option[Timestamp] = None,
                            var resolvedBy: Option[Int] = None,
                            visibility: Int = 1) extends OreModel(id, createdAt) with VisibilityChange {
  /** Self referential type */
  override type M = ProjectVisibilityChange
  /** The model's table */
  override type T = ProjectVisibilityChangeTable

  /** Render the comment as Html */
  def renderComment(): Html = Page.Render(comment)

  def created(implicit ec: ExecutionContext): OptionT[Future, User] = {
    OptionT.fromOption[Future](createdBy).flatMap(userBase.get(_))
  }

  /**
    * Set the resolvedAt time
    */
  def setResolvedAt(time: Timestamp): Future[Int] = {
    this.resolvedAt = Some(time)
    update(ResolvedAtVC)
  }

  /**
    * Set the resolvedBy user
    */
  def setResolvedBy(user: User): Future[Int] = {
    this.resolvedBy = user.id
    update(ResolvedByVC)
  }
  def setResolvedById(userId: Int): Future[Int] = {
    this.resolvedBy = Some(userId)
    update(ResolvedByVC)
  }

  /**
    * Returns a copy of this model with an updated ID and timestamp.
    *
    * @param id      ID to set
    * @param theTime Timestamp
    * @return Copy of model
    */
  override def copyWith(id: Option[Int], theTime: Option[Timestamp]): Model = this.copy(id = id, createdAt = createdAt)
}
