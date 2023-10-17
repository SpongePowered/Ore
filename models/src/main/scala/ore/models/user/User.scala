package ore.models.user

import scala.language.higherKinds

import java.time.OffsetDateTime
import java.util.Locale

import ore.data.Prompt
import ore.db._
import ore.db.access._
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.common.Named
import ore.db.impl.query.UserQueries
import ore.db.impl.schema._
import ore.db.impl.{ModelCompanionPartial, OrePostgresDriver}
import ore.models.organization.Organization
import ore.models.project.{Flag, Project}
import ore.models.user.role.{DbRole, OrganizationUserRole, ProjectUserRole}
import ore.permission._
import ore.permission.scope._
import ore.syntax._

import cats.syntax.all._
import cats.{Functor, Monad}
import slick.lifted.TableQuery

/**
  * Represents a Sponge user.
  *
  * @param id           External ID provided by authentication.
  * @param fullName     Full name of user
  * @param name         Username
  * @param email        Email
  * @param tagline      The user configured "tagline" displayed on the user page.
  */
case class User(
    private val id: ObjId[User],
    fullName: Option[String] = None,
    name: String = "",
    email: Option[String] = None,
    tagline: Option[String] = None,
    joinDate: Option[OffsetDateTime] = None,
    readPrompts: List[Prompt] = Nil,
    lang: Option[Locale] = None
) extends Named

object User extends ModelCompanionPartial[User, UserTable](TableQuery[UserTable]) {

  override def asDbModel(
      model: User,
      id: ObjId[User],
      time: ObjOffsetDateTime
  ): Model[User] = Model(model.id, time, model)

  implicit val query: ModelQuery[User] =
    ModelQuery.from(this)

  implicit val assocStarsQuery: AssociationQuery[ProjectStarsTable, User, Project] =
    AssociationQuery.from[ProjectStarsTable, User, Project](TableQuery[ProjectStarsTable])(_.userId, _.projectId)

  implicit val assocRolesQuery: AssociationQuery[UserGlobalRolesTable, User, DbRole] =
    AssociationQuery.from[UserGlobalRolesTable, User, DbRole](TableQuery[UserGlobalRolesTable])(_.userId, _.roleId)

  implicit class UserModelOps(private val self: Model[User]) extends AnyVal {

    /**
      * Returns the [[DbRole]]s that this User has.
      *
      * @return Roles the user has.
      */
    def globalRoles[F[_]](
        implicit service: ModelService[F],
        F: Functor[F]
    ): ParentAssociationAccess[UserGlobalRolesTable, User, DbRole, UserTable, DbRoleTable, F] =
      new ModelAssociationAccessImpl(OrePostgresDriver)(User, DbRole).applyParent(self.id)

    /**
      * Returns the [[Project]]s that this User is watching.
      *
      * @return Projects user is watching
      */
    def watching[F[_]](
        implicit service: ModelService[F],
        F: Functor[F]
    ): ChildAssociationAccess[ProjectWatchersTable, Project, User, ProjectTable, UserTable, F] =
      new ModelAssociationAccessImpl(OrePostgresDriver)(Project, User).applyChild(self.id)

    /**
      * Sets the "watching" status on the specified project.
      *
      * @param project  Project to update status on
      * @param watching True if watching
      */
    def setWatching[F[_]](
        project: Model[Project],
        watching: Boolean
    )(implicit service: ModelService[F], F: Monad[F]): F[Unit] = {
      val contains = self.watching.contains(project.id)
      contains.flatMap {
        case true  => if (!watching) self.watching.removeAssoc(project.id) else F.unit
        case false => if (watching) self.watching.addAssoc(project.id) else F.unit
      }
    }

    def permissionsIn[A: HasScope, F[_]: Functor](a: A)(implicit service: ModelService[F]): F[Permission] =
      permissionsIn(a.scope)

    /**
      * Returns this User's highest level of Trust.
      *
      * @return Highest level of trust
      */
    def permissionsIn[F[_]: Functor](scope: Scope = GlobalScope)(implicit service: ModelService[F]): F[Permission] = {
      val alwaysHasPermissions = Permission(
        Permission.ViewPublicInfo,
        Permission.EditOwnUserSettings,
        Permission.EditApiKeys
      )

      val conIO = scope match {
        case GlobalScope             => UserQueries.globalPermission(self.id.value).unique
        case ProjectScope(projectId) => UserQueries.projectPermission(self.id.value, projectId).unique
        case OrganizationScope(organizationId) =>
          UserQueries.organizationPermission(self.id.value, organizationId).unique
      }

      service.runDbCon(conIO).map(_ ++ alwaysHasPermissions)
    }

    /**
      * Returns all [[Project]]s owned by this user.
      *
      * @return Projects owned by user
      */
    def projects[V[_, _]: QueryView](
        view: V[ProjectTable, Model[Project]]
    ): V[ProjectTable, Model[Project]] =
      view.filterView(_.ownerId === self.id.value)

    /**
      * Returns a [[ModelView]] of [[ProjectUserRole]]s.
      *
      * @return ProjectRoles
      */
    def projectRoles[V[_, _]: QueryView](
        view: V[ProjectRoleTable, Model[ProjectUserRole]]
    ): V[ProjectRoleTable, Model[ProjectUserRole]] =
      view.filterView(_.userId === self.id.value)

    /**
      * Returns the [[Organization]]s that this User owns.
      *
      * @return Organizations user owns
      */
    def ownedOrganizations[V[_, _]: QueryView](
        view: V[OrganizationTable, Model[Organization]]
    ): V[OrganizationTable, Model[Organization]] =
      view.filterView(_.ownerId === self.id.value)

    /**
      * Returns a [[ModelView]] of [[OrganizationUserRole]]s.
      *
      * @return OrganizationRoles
      */
    def organizationRoles[V[_, _]: QueryView](
        view: V[OrganizationRoleTable, Model[OrganizationUserRole]]
    ): V[OrganizationRoleTable, Model[OrganizationUserRole]] =
      view.filterView(_.userId === self.id.value)

    /**
      * Converts this User to an [[Organization]].
      *
      * @return Organization
      */
    def toMaybeOrganization[QOptRet, SRet[_]](
        view: ModelView[QOptRet, SRet, OrganizationTable, Model[Organization]]
    ): QOptRet = view.get(self.id.value)

    /**
      * Returns the [[Flag]]s submitted by this User.
      *
      * @return Flags submitted by user
      */
    def flags[V[_, _]: QueryView](view: V[FlagTable, Model[Flag]]): V[FlagTable, Model[Flag]] =
      view.filterView(_.userId === self.id.value)

    /**
      * Returns true if the User has an unresolved [[Flag]] on the specified
      * [[Project]].
      *
      * @param project Project to check
      * @return True if has pending flag on Project
      */
    def hasUnresolvedFlagFor[QOptRet, SRet[_]](
        project: Model[Project],
        view: ModelView[QOptRet, SRet, FlagTable, Model[Flag]]
    ): SRet[Boolean] =
      flags(view).exists(f => f.projectId === project.id.value && !f.isResolved)

    /**
      * Returns this User's notifications.
      *
      * @return User notifications
      */
    def notifications[V[_, _]: QueryView](
        view: V[NotificationTable, Model[Notification]]
    ): V[NotificationTable, Model[Notification]] =
      view.filterView(_.userId === self.id.value)

    /**
      * Marks a [[Prompt]] as read by this User.
      *
      * @param prompt Prompt to mark as read
      */
    def markPromptAsRead[F[_]](prompt: Prompt)(implicit service: ModelService[F]): F[Model[User]] = {
      service.update(self)(
        _.copy(
          readPrompts = self.readPrompts :+ prompt
        )
      )
    }
  }
}
