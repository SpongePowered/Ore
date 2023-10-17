package util.syntax.mixins

import scala.language.{higherKinds, implicitConversions}

import java.nio.file.Path

import play.api.i18n.Lang
import play.api.mvc.RequestHeader

import ore.OreConfig
import ore.auth.AuthUser
import ore.db.access.ModelView
import ore.db.{Model, ModelService, ObjId}
import ore.models.organization.Organization
import ore.models.project.io.ProjectFiles
import ore.models.project.{Page, Project}
import ore.models.user.User
import ore.permission.role.Role

import cats.Functor
import cats.data.OptionT
import cats.syntax.all._

object ModelSyntax {

  class UserSyntax(private val u: User) extends AnyVal {

    def avatarUrl(implicit config: OreConfig): String = new UserObjSyntax(User).avatarUrl(u.name)

    /**
      * Returns this user's current language, or the default language if none
      * was configured.
      */
    implicit def langOrDefault: Lang = u.lang.fold(Lang.defaultLang)(Lang.apply)
  }

  class UserObjSyntax(private val u: User.type) extends AnyVal {

    def avatarUrl(name: String)(implicit config: OreConfig): String =
      if (name == "Spongie") config.sponge.logo
      else config.auth.api.avatarUrl.format(name)
  }

  class PageObjSyntax(private val p: Page.type) extends AnyVal {

    /**
      * The name of each Project's homepage.
      */
    def homeName(implicit config: OreConfig): String = config.ore.pages.homeName

    /**
      * The template body for the Home page.
      */
    def homeMessage(implicit config: OreConfig): String = config.ore.pages.homeMessage

    /**
      * The minimum amount of characters a page may have.
      */
    def minLength(implicit config: OreConfig): Int = config.ore.pages.minLen

    /**
      * The maximum amount of characters the home page may have.
      */
    def maxLength(implicit config: OreConfig): Int = config.ore.pages.maxLen

    /**
      * The maximum amount of characters a page may have.
      */
    def maxLengthPage(implicit config: OreConfig): Int = config.ore.pages.pageMaxLen
  }

  //TODO: Remove these from views
  class ProjectSyntax(private val p: Project) extends AnyVal {

    def iconUrlOrPath[F[_]](
        implicit projectFiles: ProjectFiles[F],
        F: Functor[F],
        config: OreConfig
    ): F[Either[String, Path]] =
      projectFiles.getIconPath(p).map(_.toRight(new UserObjSyntax(User).avatarUrl(p.ownerName)))

    def hasIcon[F[_]](implicit projectFiles: ProjectFiles[F], F: Functor[F]): F[Boolean] =
      projectFiles.getIconPath(p).map(_.isDefined)

    def iconUrl[F[_]](
        implicit projectFiles: ProjectFiles[F],
        F: Functor[F],
        header: RequestHeader,
        config: OreConfig
    ): F[String] =
      iconUrlOrPath.map(
        _.swap.getOrElse(controllers.project.routes.Projects.showIcon(p.ownerName, p.slug).absoluteURL())
      )
  }

  class OrganizationSyntax(private val o: Organization) extends AnyVal {

    /**
      * Returns this Organization as a [[User]].
      *
      * @return This Organization as a User
      */
    def toUser[F[_]](implicit service: ModelService[F]): OptionT[F, Model[User]] =
      ModelView.now(User).get(o.userId)
  }

  class AuthUserSyntax(private val u: AuthUser) extends AnyVal {
    def newGlobalRoles: Option[List[Role]] = u.addGroups.map { groups =>
      if (groups.trim.isEmpty) Nil
      else groups.split(",").flatMap(Role.withValueOpt).toList
    }

    def toUser: User = User(
      id = ObjId(u.id),
      fullName = None,
      name = u.username,
      email = Some(u.email),
      lang = u.lang,
      tagline = None,
      joinDate = None,
      readPrompts = Nil
    )
  }
}
