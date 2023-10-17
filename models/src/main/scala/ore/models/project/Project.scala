package ore.models.project

import scala.language.higherKinds

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.Locale

import ore.data.project.{Category, FlagReason, ProjectNamespace}
import ore.db._
import ore.db.access._
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.common._
import ore.db.impl.schema._
import ore.db.impl.{DefaultModelCompanion, OrePostgresDriver}
import ore.member.{Joinable, MembershipDossier}
import ore.models.admin.ProjectVisibilityChange
import ore.models.api.ProjectApiKey
import ore.models.project.Project.ProjectSettings
import ore.models.user.role.ProjectUserRole
import ore.models.user.{User, UserOwned}
import ore.permission.role.Role
import ore.permission.scope.HasScope
import ore.syntax._
import ore.util.{StringLocaleFormatterUtils, StringUtils}

import cats.syntax.all._
import cats.{Functor, Monad, MonadError, Parallel}
import io.circe.Json
import io.circe.generic.JsonCodec
import io.circe.syntax._
import slick.lifted.TableQuery

/**
  * Represents an Ore package.
  *
  * <p>Note: As a general rule, do not handle actions / results in model classes</p>
  *
  * @param pluginId               Plugin ID
  * @param ownerName              The owner Author for this project
  * @param ownerId                User ID of Project owner
  * @param name                   Name of plugin
  * @param topicId                ID of forum topic
  * @param postId                 ID of forum topic post ID
  * @param visibility             Whether this project is visible to the default user
  * @param notes                  JSON notes
  */
case class Project(
    pluginId: String,
    ownerName: String,
    ownerId: DbRef[User],
    name: String,
    category: Category = Category.Undefined,
    description: Option[String],
    topicId: Option[Int] = None,
    postId: Option[Int] = None,
    visibility: Visibility = Visibility.Public,
    notes: Json = Json.obj(),
    settings: ProjectSettings = ProjectSettings()
) extends Named
    with Describable
    with Visitable {

  val slug: String = StringUtils.slugify(name)

  def namespace: ProjectNamespace = ProjectNamespace(ownerName, slug)

  /**
    * Returns the base URL for this Project.
    *
    * @return Base URL for project
    */
  override def url: String = namespace.toString

  /**
    * Get all messages
    * @return
    */
  def decodeNotes: Seq[Note] =
    notes.hcursor.getOrElse[Seq[Note]]("messages")(Nil).toTry.get //Should be safe. If it's not we got bigger problems

  def isOwner(user: Model[User]): Boolean = user.id.value == ownerId
}

/**
  * This modal is needed to convert the json
  */
@JsonCodec case class Note(message: String, user: DbRef[User], time: Long = System.currentTimeMillis()) {
  def printTime(implicit locale: Locale): String =
    StringLocaleFormatterUtils.prettifyDateAndTime(OffsetDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.UTC))
}

object Project extends DefaultModelCompanion[Project, ProjectTable](TableQuery[ProjectTable]) {

  case class ProjectSettings(
      keywords: List[String] = Nil,
      homepage: Option[String] = None,
      issues: Option[String] = None,
      source: Option[String] = None,
      support: Option[String] = None,
      licenseName: Option[String] = None,
      licenseUrl: Option[String] = None,
      forumSync: Boolean = true
  )

  implicit val query: ModelQuery[Project] = ModelQuery.from(this)

  implicit val assocWatchersQuery: AssociationQuery[ProjectWatchersTable, Project, User] =
    AssociationQuery.from[ProjectWatchersTable, Project, User](TableQuery[ProjectWatchersTable])(_.projectId, _.userId)

  implicit val hasScope: HasScope[Model[Project]] = HasScope.projectScope(_.id)

  implicit def projectHideable[F[_]](
      implicit service: ModelService[F],
      F: Monad[F],
      parallel: Parallel[F]
  ): Hideable.Aux[F, Project, ProjectVisibilityChange, ProjectVisibilityChangeTable] = new Hideable[F, Project] {
    override type MVisibilityChange      = ProjectVisibilityChange
    override type MVisibilityChangeTable = ProjectVisibilityChangeTable

    override def visibility(m: Project): Visibility = m.visibility

    /**
      * Sets whether this project is visible.
      *
      * @param visibility True if visible
      */
    override def setVisibility(m: Model[Project])(
        visibility: Visibility,
        comment: String,
        creator: DbRef[User]
    ): F[(Model[Project], Model[ProjectVisibilityChange])] = {
      val updateOldChange = lastVisibilityChange(m)(ModelView.now(ProjectVisibilityChange))
        .semiflatMap { vc =>
          service.update(vc)(
            _.copy(
              resolvedAt = Some(OffsetDateTime.now()),
              resolvedBy = Some(creator)
            )
          )
        }
        .cata((), _ => ())

      val createNewChange = service.insert(
        ProjectVisibilityChange(
          Some(creator),
          m.id,
          comment,
          None,
          None,
          visibility
        )
      )

      val updateProject = service.update(m)(
        _.copy(
          visibility = visibility
        )
      )

      updateOldChange *> (updateProject, createNewChange).parTupled
    }

    /**
      * Get VisibilityChanges
      */
    override def visibilityChanges[V[_, _]: QueryView](m: Model[Project])(
        view: V[ProjectVisibilityChangeTable, Model[ProjectVisibilityChange]]
    ): V[ProjectVisibilityChangeTable, Model[ProjectVisibilityChange]] = view.filterView(_.projectId === m.id.value)
  }

  implicit val isUserOwned: UserOwned[Project] = (a: Project) => a.ownerId

  implicit def projectJoinable[F[_]](
      implicit service: ModelService[F],
      F: MonadError[F, Throwable],
      par: Parallel[F]
  ): Joinable.Aux[F, Project, ProjectUserRole, ProjectRoleTable] = new Joinable[F, Project] {
    type RoleType      = ProjectUserRole
    type RoleTypeTable = ProjectRoleTable

    override def transferOwner(m: Model[Project])(newOwner: DbRef[User]): F[Model[Project]] = {
      // Down-grade current owner to "Developer"
      val oldOwner = m.ownerId
      for {
        newOwnerUser <- ModelView
          .now(User)
          .get(newOwner)
          .getOrElseF(F.raiseError(new Exception("Could not find user to transfer owner to")))
        t2 <- (this.memberships.getMembership(m)(oldOwner), this.memberships.getMembership(m)(newOwner)).parTupled
        (ownerRoles, userRoles) = t2
        setOwner <- setOwner(m)(newOwnerUser)
        _ <- ownerRoles
          .filter(_.role == Role.ProjectOwner)
          .toVector
          .parTraverse(role => service.update(role)(_.copy(role = Role.ProjectAdmin)))
        _ <- userRoles.toVector.parTraverse(role => service.update(role)(_.copy(role = Role.ProjectOwner)))
      } yield setOwner
    }

    private def setOwner(m: Model[Project])(user: Model[User]): F[Model[Project]] = {
      service.update(m)(
        _.copy(
          ownerId = user.id
        )
      )
    }

    override def memberships: MembershipDossier.Aux[F, Project, RoleType, RoleTypeTable] =
      MembershipDossier.projectHasMemberships

    override def userOwned: UserOwned[Project] = isUserOwned
  }

  implicit class ProjectModelOps(private val self: Model[Project]) extends AnyVal {

    /**
      * Returns ModelAccess to the user's who are watching this project.
      *
      * @return Users watching project
      */
    def watchers[F[_]: ModelService: Functor]
        : ParentAssociationAccess[ProjectWatchersTable, Project, User, ProjectTable, UserTable, F] =
      new ModelAssociationAccessImpl(OrePostgresDriver)(Project, User).applyParent(self.id)

    /**
      * Returns [[ore.db.access.ChildAssociationAccess]] to [[User]]s who have starred this
      * project.
      *
      * @return Users who have starred this project
      */
    def stars[F[_]: ModelService: Functor]
        : ChildAssociationAccess[ProjectStarsTable, User, Project, UserTable, ProjectTable, F] =
      new ModelAssociationAccessImpl[ProjectStarsTable, User, Project, UserTable, ProjectTable, F](
        OrePostgresDriver
      )(
        User,
        Project
      ).applyChild(self.id)

    /**
      * Sets the "starred" state of this Project for the specified User.
      *
      * @param user User to set starred state of
      */
    def toggleStarredBy[F[_]](
        user: Model[User]
    )(implicit service: ModelService[F], F: Monad[F]): F[Project] =
      for {
        contains <- self.stars.contains(user.id)
        _ <- if (contains)
          self.stars.removeAssoc(user.id)
        else
          self.stars.addAssoc(user.id)
      } yield self

    /**
      * Returns all flags on this project.
      *
      * @return Flags on project
      */
    def flags[V[_, _]: QueryView](view: V[FlagTable, Model[Flag]]): V[FlagTable, Model[Flag]] =
      view.filterView(_.projectId === self.id.value)

    /**
      * Submits a flag on this project for the specified user.
      *
      * @param user   Flagger
      * @param reason Reason for flagging
      */
    def flagFor[F[_]](user: Model[User], reason: FlagReason, comment: String)(
        implicit service: ModelService[F]
    ): F[Model[Flag]] = {
      val userId = user.id.value
      require(userId != self.ownerId, "cannot flag own project")
      service.insert(Flag(self.id, user.id, reason, comment))
    }

    /**
      * Returns all versions in this project.
      *
      * @return Versions in project
      */
    def versions[V[_, _]: QueryView](view: V[VersionTable, Model[Version]]): V[VersionTable, Model[Version]] =
      view.filterView(_.projectId === self.id.value)

    /**
      * Returns the pages in this Project.
      *
      * @return Pages in project
      */
    def pages[V[_, _]: QueryView](view: V[PageTable, Model[Page]]): V[PageTable, Model[Page]] =
      view.filterView(_.projectId === self.id.value)

    /**
      * Returns the parentless, root, pages for this project.
      *
      * @return Root pages of project
      */
    def rootPages[V[_, _]: QueryView](view: V[PageTable, Model[Page]]): V[PageTable, Model[Page]] =
      view.sortView(_.name).filterView(p => p.projectId === self.id.value && p.parentId.isEmpty)

    def apiKeys[V[_, _]: QueryView](
        view: V[ProjectApiKeyTable, Model[ProjectApiKey]]
    ): V[ProjectApiKeyTable, Model[ProjectApiKey]] =
      view.filterView(_.projectId === self.id.value)

    /**
      * Add new note
      */
    def addNote[F[_]](message: Note)(implicit service: ModelService[F]): F[Model[Project]] = {
      val messages = self.decodeNotes :+ message
      service.update(self)(
        _.copy(
          notes = Json.obj(
            "messages" := messages
          )
        )
      )
    }
  }
}
