package ore.db.impl.schema

import ore.db.DbRef
import ore.db.impl.OrePostgresDriver.api._
import ore.models.organization.Organization
import ore.models.project.{Page, Project, Version}
import ore.models.user._

import com.github.tminglei.slickpg.InetString

abstract class LoggedActionTable[M, Ctx](tag: Tag, tableName: String, ctxIdColumnName: String)
    extends ModelTable[M](tag, tableName) {

  def userId   = column[DbRef[User]]("user_id")
  def address  = column[InetString]("address")
  def action   = column[LoggedActionType[Ctx]]("action")
  def ctxId    = column[DbRef[Ctx]](ctxIdColumnName)
  def newState = column[String]("new_state")
  def oldState = column[String]("old_state")

  def common =
    (
      userId.?,
      address,
      action,
      ctxId.?,
      newState,
      oldState
    ).<>((LoggedActionCommon.apply[Ctx] _).tupled, LoggedActionCommon.unapply)
}

class LoggedActionProjectTable(tag: Tag)
    extends LoggedActionTable[LoggedActionProject, Project](tag, "logged_actions_project", "project_id") {
  override def * =
    (id.?, createdAt.?, common).<>(mkApply(LoggedActionProject.apply), mkUnapply(LoggedActionProject.unapply))
}

class LoggedActionVersionTable(tag: Tag)
    extends LoggedActionTable[LoggedActionVersion, Version](tag, "logged_actions_version", "version_id") {
  def projectId = column[DbRef[Project]]("project_id")

  override def * =
    (id.?, createdAt.?, (common, projectId.?)).<>(
      mkApply((LoggedActionVersion.apply _).tupled),
      mkUnapply(
        LoggedActionVersion.unapply
      )
    )
}

class LoggedActionPageTable(tag: Tag)
    extends LoggedActionTable[LoggedActionPage, Page](tag, "logged_actions_page", "page_id") {
  def projectId = column[DbRef[Project]]("project_id")

  override def * =
    (id.?, createdAt.?, (common, projectId.?)).<>(
      mkApply((LoggedActionPage.apply _).tupled),
      mkUnapply(
        LoggedActionPage.unapply
      )
    )
}

class LoggedActionUserTable(tag: Tag)
    extends LoggedActionTable[LoggedActionUser, User](tag, "logged_actions_user", "subject_id") {
  override def * =
    (id.?, createdAt.?, common).<>(mkApply(LoggedActionUser.apply), mkUnapply(LoggedActionUser.unapply))
}

class LoggedActionOrganizationTable(tag: Tag)
    extends LoggedActionTable[LoggedActionOrganization, Organization](
      tag,
      "logged_actions_organization",
      "organization_id"
    ) {
  override def * =
    (id.?, createdAt.?, common).<>(
      mkApply(LoggedActionOrganization.apply),
      mkUnapply(
        LoggedActionOrganization.unapply
      )
    )
}
