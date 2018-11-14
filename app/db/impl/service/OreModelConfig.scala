package db.impl.service

import db.impl.OrePostgresDriver.api._
import db.impl.schema._
import db.table.ModelAssociation
import db.{ModelSchema, ObjectReference}
import models.admin.{ProjectLog, ProjectLogEntry, ProjectVisibilityChange, Review, VersionVisibilityChange}
import models.api.ProjectApiKey
import models.project._
import models.statistic.{ProjectView, VersionDownload}
import models.user._
import models.user.role.{DbRole, OrganizationUserRole, ProjectUserRole}
import ore.{OreConfig, OreEnv}

import slick.jdbc.JdbcProfile

abstract class OreModelConfig(driver: JdbcProfile, env: OreEnv, config: OreConfig)
    extends OreDBOs(driver, env, config) {

  val projectWatchers = new ModelAssociation[ProjectWatchersTable](
    this,
    _.projectId,
    _.userId,
    classOf[ProjectWatchersTable],
    TableQuery[ProjectWatchersTable]
  )

  val projectMembers = new ModelAssociation[ProjectMembersTable](
    this,
    _.projectId,
    _.userId,
    classOf[ProjectMembersTable],
    TableQuery[ProjectMembersTable]
  )

  val organizationMembers = new ModelAssociation[OrganizationMembersTable](
    this,
    _.userId,
    _.organizationId,
    classOf[OrganizationMembersTable],
    TableQuery[OrganizationMembersTable]
  )

  val stars = new ModelAssociation[ProjectStarsTable](
    this,
    _.userId,
    _.projectId,
    classOf[ProjectStarsTable],
    TableQuery[ProjectStarsTable]
  )

  val globalRoles = new ModelAssociation[UserGlobalRolesTable](
    this,
    _.userId,
    _.roleId,
    classOf[UserGlobalRolesTable],
    TableQuery[UserGlobalRolesTable]
  )

  // Begin schemas

  val UserSchema: ModelSchema[User] = new UserSchema(this)
    .withChildren[Project](classOf[Project], _.userId)
    .withChildren[ProjectUserRole](classOf[ProjectUserRole], _.userId)
    .withChildren[OrganizationUserRole](classOf[OrganizationUserRole], _.userId)
    .withChildren[Flag](classOf[Flag], _.userId)
    .withChildren[Notification](classOf[Notification], _.userId)
    .withChildren[Organization](classOf[Organization], _.userId)
    .withAssociation[ProjectWatchersTable, Project](
      association = this.projectWatchers,
      selfReference = _.userId,
      targetClass = classOf[Project],
      targetReference = _.projectId
    )
    .withAssociation[ProjectMembersTable, Project](
      association = this.projectMembers,
      selfReference = _.userId,
      targetClass = classOf[Project],
      targetReference = _.projectId
    )
    .withAssociation[OrganizationMembersTable, Organization](
      association = this.organizationMembers,
      selfReference = _.userId,
      targetClass = classOf[Organization],
      targetReference = _.organizationId
    )
    .withAssociation[ProjectStarsTable, Project](
      association = this.stars,
      selfReference = _.userId,
      targetClass = classOf[Project],
      targetReference = _.projectId
    )
    .withAssociation[UserGlobalRolesTable, DbRole](
      association = this.globalRoles,
      selfReference = _.userId,
      targetClass = classOf[DbRole],
      targetReference = _.roleId
    )

  val SessionSchema = new ModelSchema[models.user.Session](this, classOf[models.user.Session], TableQuery[SessionTable])

  val SignOnSchema = new ModelSchema[SignOn](this, classOf[SignOn], TableQuery[SignOnTable])

  val ProjectRolesSchema =
    new ModelSchema[ProjectUserRole](this, classOf[ProjectUserRole], TableQuery[ProjectRoleTable])

  val ProjectVisibilityChangeSchema = new ModelSchema[ProjectVisibilityChange](
    this,
    classOf[ProjectVisibilityChange],
    TableQuery[ProjectVisibilityChangeTable]
  )
  val VersionVisibilityChangeSchema = new ModelSchema[VersionVisibilityChange](
    this,
    classOf[VersionVisibilityChange],
    TableQuery[VersionVisibilityChangeTable]
  )

  val ProjectSchema: ModelSchema[Project] = new ProjectSchema(this, Users)
    .withChildren[Channel](classOf[Channel], _.projectId)
    .withChildren[Version](classOf[Version], _.projectId)
    .withChildren[Page](classOf[Page], _.projectId)
    .withChildren[Flag](classOf[Flag], _.projectId)
    .withChildren[ProjectUserRole](classOf[ProjectUserRole], _.projectId)
    .withChildren[ProjectView](classOf[ProjectView], _.modelId)
    .withChildren[ProjectApiKey](classOf[ProjectApiKey], _.projectId)
    .withChildren[ProjectVisibilityChange](classOf[ProjectVisibilityChange], _.projectId)
    .withAssociation[ProjectWatchersTable, User](
      association = this.projectWatchers,
      selfReference = _.projectId,
      targetClass = classOf[User],
      targetReference = _.userId
    )
    .withAssociation[ProjectMembersTable, User](
      association = this.projectMembers,
      selfReference = _.projectId,
      targetClass = classOf[User],
      targetReference = _.userId
    )
    .withAssociation[ProjectStarsTable, User](
      association = this.stars,
      selfReference = _.projectId,
      targetClass = classOf[User],
      targetReference = _.userId
    )

  val ProjectSettingsSchema =
    new ModelSchema[ProjectSettings](this, classOf[ProjectSettings], TableQuery[ProjectSettingsTable])

  val ProjectLogSchema: ModelSchema[ProjectLog] =
    new ModelSchema[ProjectLog](this, classOf[ProjectLog], TableQuery[ProjectLogTable])
      .withChildren[ProjectLogEntry](classOf[ProjectLogEntry], _.logId)

  val ProjectLogEntrySchema =
    new ModelSchema[ProjectLogEntry](this, classOf[ProjectLogEntry], TableQuery[ProjectLogEntryTable])

  val FlagSchema = new ModelSchema[Flag](this, classOf[Flag], TableQuery[FlagTable])

  case object ViewSchema
      extends ModelSchema[ProjectView](this, classOf[ProjectView], TableQuery[ProjectViewsTable])
      with StatSchema[ProjectView] {
    override def setUserId(m: ProjectView, id: ObjectReference): ProjectView = m.copy(userId = Some(id))
  }

  val ReviewSchema = new ModelSchema[Review](this, classOf[Review], TableQuery[ReviewTable])

  val VersionSchema: ModelSchema[Version] = new VersionSchema(this)
    .withChildren[VersionDownload](classOf[VersionDownload], _.modelId)
    .withChildren[Review](classOf[Review], _.versionId)
    .withChildren[VersionVisibilityChange](classOf[VersionVisibilityChange], _.versionId)

  val DownloadWarningSchema =
    new ModelSchema[DownloadWarning](this, classOf[DownloadWarning], TableQuery[DownloadWarningsTable])

  val UnsafeDownloadSchema =
    new ModelSchema[UnsafeDownload](this, classOf[UnsafeDownload], TableQuery[UnsafeDownloadsTable])

  case object DownloadSchema
      extends ModelSchema[VersionDownload](this, classOf[VersionDownload], TableQuery[VersionDownloadsTable])
      with StatSchema[VersionDownload] {
    override def setUserId(m: VersionDownload, id: ObjectReference): VersionDownload = m.copy(userId = Some(id))
  }

  val ChannelSchema: ModelSchema[Channel] = new ModelSchema[Channel](this, classOf[Channel], TableQuery[ChannelTable])
    .withChildren[Version](classOf[Version], _.channelId)

  val VersionTagSchema = new ModelSchema[VersionTag](this, classOf[VersionTag], TableQuery[VersionTagTable])

  val PageSchema = new PageSchema(this)

  val NotificationSchema = new ModelSchema[Notification](this, classOf[Notification], TableQuery[NotificationTable])

  val OrganizationSchema: ModelSchema[Organization] =
    new ModelSchema[Organization](this, classOf[Organization], TableQuery[OrganizationTable])
      .withChildren[Project](classOf[Project], _.userId)
      .withChildren[OrganizationUserRole](classOf[OrganizationUserRole], _.organizationId)
      .withAssociation[OrganizationMembersTable, User](
        association = this.organizationMembers,
        selfReference = _.organizationId,
        targetClass = classOf[User],
        targetReference = _.userId
      )

  val OrganizationRoleSchema =
    new ModelSchema[OrganizationUserRole](this, classOf[OrganizationUserRole], TableQuery[OrganizationRoleTable])

  val ProjectApiKeySchema = new ModelSchema[ProjectApiKey](this, classOf[ProjectApiKey], TableQuery[ProjectApiKeyTable])

  val UserActionLogSchema =
    new ModelSchema[LoggedActionModel](this, classOf[LoggedActionModel], TableQuery[LoggedActionTable])

  val DbRoleSchema: ModelSchema[DbRole] = new ModelSchema[DbRole](this, classOf[DbRole], TableQuery[DbRoleTable])
    .withAssociation[UserGlobalRolesTable, User](
      association = this.globalRoles,
      selfReference = _.roleId,
      targetClass = classOf[User],
      targetReference = _.userId
    )

}
