package models.project

import java.sql.Timestamp
import java.time.Instant

import play.api.i18n.Messages
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.twirl.api.Html

import db.access.{ModelAccess, ModelAssociationAccess, ModelAssociationAccessImpl}
import db.impl.OrePostgresDriver.api._
import db.impl.model.common.{Describable, Downloadable, Hideable, Named}
import db.impl.schema.{
  ProjectMembersTable,
  ProjectRoleTable,
  ProjectStarsTable,
  ProjectTable,
  ProjectTableMain,
  ProjectWatchersTable
}
import db.{AssociationQuery, DbRef, InsertFunc, Model, ModelQuery, ModelService, ObjId, ObjTimestamp}
import models.admin.{ProjectLog, ProjectVisibilityChange}
import models.api.ProjectApiKey
import models.project.Visibility.Public
import models.statistic.ProjectView
import models.user.User
import models.user.role.ProjectUserRole
import ore.permission.role.Role
import ore.permission.scope.HasScope
import ore.project.{Category, FlagReason, ProjectMember}
import ore.user.MembershipDossier
import ore.{Joinable, OreConfig, Visitable}
import _root_.util.StringUtils
import _root_.util.StringUtils._
import _root_.util.syntax._

import cats.data.OptionT
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import com.google.common.base.Preconditions._
import slick.lifted
import slick.lifted.{Rep, TableQuery}

/**
  * Represents an Ore package.
  *
  * <p>Note: As a general rule, do not handle actions / results in model classes</p>
  *
  * @param id                     Unique identifier
  * @param createdAt              Instant of creation
  * @param pluginId               Plugin ID
  * @param ownerName              The owner Author for this project
  * @param ownerId                User ID of Project owner
  * @param name                   Name of plugin
  * @param slug                   URL slug
  * @param recommendedVersionId   The ID of this project's recommended version
  * @param starCount                  Star count
  * @param viewCount                  View count
  * @param downloadCount              How many times this project has been downloaded in total
  * @param topicId                ID of forum topic
  * @param postId                 ID of forum topic post ID
  * @param isTopicDirty           Whether this project's forum topic needs to be updated
  * @param visibility             Whether this project is visible to the default user
  * @param lastUpdated            Instant of last version release
  * @param notes                  JSON notes
  */
case class Project private (
    id: ObjId[Project],
    createdAt: ObjTimestamp,
    pluginId: String,
    ownerName: String,
    ownerId: DbRef[User],
    name: String,
    slug: String,
    recommendedVersionId: Option[DbRef[Version]],
    category: Category,
    description: Option[String],
    starCount: Long,
    viewCount: Long,
    downloadCount: Long,
    topicId: Option[Int],
    postId: Option[Int],
    isTopicDirty: Boolean,
    visibility: Visibility,
    lastUpdated: Timestamp,
    notes: JsValue
) extends Model
    with Downloadable
    with Named
    with Describable
    with Hideable
    with Joinable[ProjectMember, Project]
    with Visitable {

  override type M                     = Project
  override type T                     = ProjectTable
  override type ModelVisibilityChange = ProjectVisibilityChange

  /**
    * Contains all information for [[User]] memberships.
    */
  override def memberships(
      implicit service: ModelService
  ): MembershipDossier.Aux[IO, Project, ProjectUserRole, ProjectMember] =
    MembershipDossier[IO, Project]

  def isOwner(user: User): Boolean = user.id.value == ownerId

  /**
    * Returns the owner [[ProjectMember]] of this project.
    *
    * @return Owner Member of project
    */
  override def owner(implicit service: ModelService): ProjectMember = new ProjectMember(this, this.ownerId)

  override def transferOwner(
      member: ProjectMember
  )(implicit service: ModelService, cs: ContextShift[IO]): IO[Project] = {
    // Down-grade current owner to "Developer"
    import cats.instances.vector._
    for {
      t1 <- (this.owner.user, member.user).parTupled
      (owner, user) = t1
      t2 <- (this.memberships.getRoles(this, owner), this.memberships.getRoles(this, user)).parTupled
      (ownerRoles, userRoles) = t2
      setOwner <- this.setOwner(user)
      _ <- ownerRoles
        .filter(_.role == Role.ProjectOwner)
        .toVector
        .parTraverse(role => service.update(role.copy(role = Role.ProjectDeveloper)))
      _ <- userRoles.toVector.parTraverse(role => service.update(role.copy(role = Role.ProjectOwner)))
    } yield setOwner
  }

  /**
    * Sets the [[User]] that owns this Project.
    *
    * @param user User that owns project
    */
  def setOwner(user: User)(implicit service: ModelService): IO[Project] = {
    checkNotNull(user, "null user", "")
    service.update(
      copy(
        ownerId = user.id,
        ownerName = user.name
      )
    )
  }

  /**
    * Returns ModelAccess to the user's who are watching this project.
    *
    * @return Users watching project
    */
  def watchers(
      implicit service: ModelService
  ): ModelAssociationAccess[ProjectWatchersTable, Project, User, IO] =
    new ModelAssociationAccessImpl

  def namespace: String = this.ownerName + '/' + this.slug

  /**
    * Returns the base URL for this Project.
    *
    * @return Base URL for project
    */
  override def url: String = this.ownerName + '/' + this.slug

  /**
    * Returns this [[Project]]'s [[ProjectSettings]].
    *
    * @return Project settings
    */
  def settings(implicit service: ModelService): IO[ProjectSettings] =
    service
      .access[ProjectSettings]()
      .find(_.projectId === this.id.value)
      .getOrElse(throw new NoSuchElementException("Get on None")) // scalafix:ok

  /**
    * Sets whether this project is visible.
    *
    * @param visibility True if visible
    */
  def setVisibility(visibility: Visibility, comment: String, creator: DbRef[User])(
      implicit service: ModelService,
      cs: ContextShift[IO]
  ): IO[(Project, ProjectVisibilityChange)] = {
    val updateOldChange = lastVisibilityChange
      .semiflatMap { vc =>
        service.update(
          vc.copy(
            resolvedAt = Some(Timestamp.from(Instant.now())),
            resolvedBy = Some(creator)
          )
        )
      }
      .cata((), _ => ())

    val createNewChange = service
      .access[ProjectVisibilityChange]()
      .add(
        ProjectVisibilityChange.partial(
          Some(creator),
          this.id,
          comment,
          None,
          None,
          visibility
        )
      )

    val updateProject = service.update(
      copy(
        visibility = visibility
      )
    )

    updateOldChange *> (updateProject, createNewChange).parTupled
  }

  /**
    * Get VisibilityChanges
    */
  override def visibilityChanges(implicit service: ModelService): ModelAccess[ProjectVisibilityChange] =
    service.access(_.projectId === id.value)

  /**
    * Returns [[db.access.ModelAccess]] to [[User]]s who have starred this
    * project.
    *
    * @return Users who have starred this project
    */
  def stars(
      implicit service: ModelService
  ): ModelAssociationAccess[ProjectStarsTable, User, Project, IO] = new ModelAssociationAccessImpl

  /**
    * Sets the "starred" state of this Project for the specified User.
    *
    * @param user User to set starred state of
    * @param starred True if should star
    */
  def setStarredBy(
      user: User,
      starred: Boolean
  )(implicit service: ModelService): IO[Project] = {
    checkNotNull(user, "null user", "")
    for {
      contains <- this.stars.contains(user, this)
      res <- if (starred != contains) {
        if (contains) stars.removeAssoc(user, this) *> service.update(copy(starCount = starCount - 1))
        else stars.addAssoc(user, this) *> service.update(copy(starCount = starCount + 1))
      } else IO.pure(this)
    } yield res
  }

  /**
    * Returns the record of unique Project views.
    *
    * @return Unique project views
    */
  def views(implicit service: ModelService): ModelAccess[ProjectView] = service.access(_.modelId === id.value)

  /**
    * Adds a view to this Project.
    */
  def addView(implicit service: ModelService): IO[Project] =
    service.update(copy(viewCount = viewCount + 1))

  /**
    * Increments this Project's downloadc count by one.
    *
    * @return IO result
    */
  def addDownload(implicit service: ModelService): IO[Project] =
    service.update(copy(downloadCount = downloadCount + 1))

  /**
    * Returns all flags on this project.
    *
    * @return Flags on project
    */
  def flags(implicit service: ModelService): ModelAccess[Flag] = service.access(_.projectId === id.value)

  /**
    * Submits a flag on this project for the specified user.
    *
    * @param user   Flagger
    * @param reason Reason for flagging
    */
  def flagFor(user: User, reason: FlagReason, comment: String)(
      implicit service: ModelService
  ): IO[Flag] = {
    checkNotNull(user, "null user", "")
    checkNotNull(reason, "null reason", "")
    val userId = user.id.value
    checkArgument(userId != this.ownerId, "cannot flag own project", "")
    service.access[Flag]().add(Flag.partial(this.id, user.id, reason, comment))
  }

  /**
    * Returns the Channels in this Project.
    *
    * @return Channels in project
    */
  def channels(implicit service: ModelService): ModelAccess[Channel] = service.access(_.projectId === id.value)

  /**
    * Returns all versions in this project.
    *
    * @return Versions in project
    */
  def versions(implicit service: ModelService): ModelAccess[Version] = service.access(_.projectId === id.value)

  /**
    * Returns this Project's recommended version.
    *
    * @return Recommended version
    */
  def recommendedVersion(implicit service: ModelService): OptionT[IO, Version] =
    OptionT.fromOption[IO](recommendedVersionId).flatMap(versions.get)

  /**
    * Returns the pages in this Project.
    *
    * @return Pages in project
    */
  def pages(implicit service: ModelService): ModelAccess[Page] = service.access(_.projectId === id.value)

  private def getOrInsert(name: String, parentId: Option[DbRef[Page]])(
      page: InsertFunc[Page]
  )(implicit service: ModelService): IO[Page] = {
    def like =
      service.find[Page] { p =>
        p.projectId === this.id.value && p.name.toLowerCase === name.toLowerCase && parentId.fold(
          p.parentId.isEmpty
        )(parentId => (p.parentId === parentId).getOrElse(false: Rep[Boolean]))
      }

    like.value.flatMap {
      case Some(u) => IO.pure(u)
      case None    => service.insert(page)
    }
  }

  /**
    * Returns this Project's home page.
    *
    * @return Project home page
    */
  def homePage(implicit service: ModelService, config: OreConfig): IO[Page] = {
    val page =
      Page.partial(this.id, Page.homeName, Page.template(this.name, Page.homeMessage), isDeletable = false, None)
    getOrInsert(Page.homeName, None)(page)
  }

  /**
    * Returns true if a page with the specified name exists.
    *
    * @param name   Page name
    * @return       True if exists
    */
  def pageExists(name: String)(implicit service: ModelService): IO[Boolean] =
    this.pages.exists(_.name === name)

  /**
    * Returns the specified Page or creates it if it doesn't exist.
    *
    * @param name   Page name
    * @return       Page with name or new name if it doesn't exist
    */
  def getOrCreatePage(
      name: String,
      parentId: Option[DbRef[Page]],
      content: Option[String] = None
  )(implicit config: OreConfig, service: ModelService): IO[Page] = {
    checkNotNull(name, "null name", "")
    val c = content match {
      case None => Page.template(name, Page.homeMessage)
      case Some(text) =>
        checkNotNull(text, "null contents", "")
        checkArgument(text.length <= Page.maxLengthPage, "contents too long", "")
        text
    }
    val page = Page.partial(this.id, name, c, isDeletable = true, parentId)
    getOrInsert(name, parentId)(page)
  }

  /**
    * Returns the parentless, root, pages for this project.
    *
    * @return Root pages of project
    */
  def rootPages(implicit service: ModelService): IO[Seq[Page]] =
    service.access[Page]().sorted(_.name, p => p.projectId === this.id.value && p.parentId.isEmpty)

  def logger(implicit service: ModelService): IO[ProjectLog] = {
    val loggers = service.access[ProjectLog]()
    loggers.find(_.projectId === this.id.value).getOrElseF(loggers.add(ProjectLog.partial(id)))
  }

  def apiKeys(implicit service: ModelService): ModelAccess[ProjectApiKey] = service.access(_.projectId === id.value)

  /**
    * Add new note
    */
  def addNote(message: Note)(implicit service: ModelService): IO[Project] = {
    val messages = decodeNotes :+ message
    service.update(
      copy(
        notes = JsObject(
          Seq("messages" -> Json.toJson(messages))
        )
      )
    )
  }

  /**
    * Get all messages
    * @return
    */
  def decodeNotes: Seq[Note] = (notes \ "messages").asOpt[Seq[Note]].getOrElse(Nil)
}

/**
  * This modal is needed to convert the json
  */
case class Note(message: String, user: DbRef[User], time: Long = System.currentTimeMillis()) {
  def printTime(implicit oreConfig: Messages): String = StringUtils.prettifyDateAndTime(new Timestamp(time))
  def render(implicit oreConfig: OreConfig): Html     = Page.render(message)
}
object Note {
  implicit val noteWrites: Writes[Note] = (note: Note) =>
    Json.obj(
      "message" -> note.message,
      "user"    -> note.user,
      "time"    -> note.time
  )

  implicit val notesRead: Reads[Note] =
    (JsPath \ "message")
      .read[String]
      .and((JsPath \ "user").read[DbRef[User]])
      .and((JsPath \ "time").read[Long])(Note.apply _)
}

object Project {

  def partial(
      pluginId: String,
      ownerName: String,
      ownerId: DbRef[User],
      name: String,
      slug: String,
      recommendedVersionId: Option[DbRef[Version]] = None,
      category: Category = Category.Undefined,
      description: Option[String] = None,
      starCount: Long = 0,
      viewCount: Long = 0,
      downloadCount: Long = 0,
      topicId: Option[Int] = None,
      postId: Option[Int] = None,
      isTopicDirty: Boolean = false,
      visibility: Visibility = Public,
      lastUpdated: Timestamp = Timestamp.from(Instant.now()),
      notes: JsValue = JsObject.empty
  ): InsertFunc[Project] =
    (id, time) =>
      Project(
        id,
        time,
        pluginId,
        ownerName,
        ownerId,
        compact(name),
        slugify(slug),
        recommendedVersionId,
        category,
        description,
        starCount,
        viewCount,
        downloadCount,
        topicId,
        postId,
        isTopicDirty,
        visibility,
        lastUpdated,
        notes
    )

  implicit val query: ModelQuery[Project] =
    ModelQuery.from[Project](
      TableQuery[ProjectTableMain],
      (obj, id, time) => obj.copy(id = id, createdAt = time, lastUpdated = time)
    )

  implicit val assocWatchersQuery: AssociationQuery[ProjectWatchersTable, Project, User] =
    AssociationQuery.from[ProjectWatchersTable, Project, User](TableQuery[ProjectWatchersTable])(_.projectId, _.userId)

  implicit val hasScope: HasScope[Project] = HasScope.projectScope(_.id.value)

  private def queryRoleForTrust(projectId: Rep[DbRef[Project]], userId: Rep[DbRef[User]]) = {
    val q = for {
      m <- TableQuery[ProjectMembersTable] if m.projectId === projectId && m.userId === userId
      r <- TableQuery[ProjectRoleTable] if m.userId === r.userId && r.projectId === projectId
    } yield r.roleType
    q.to[Set]
  }

  lazy val roleForTrustQuery = lifted.Compiled(queryRoleForTrust _)
}
