package db.impl.access

import scala.language.higherKinds

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

import play.api.mvc.Request

import ore.OreConfig
import ore.auth.SpongeAuthApi
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.{Model, ModelService}
import ore.models.organization.Organization
import ore.models.user.{Session, User}
import ore.permission.Permission
import ore.util.OreMDC
import ore.util.StringUtils._
import util.syntax._

import cats.Monad
import cats.data.{EitherT, OptionT}
import cats.syntax.all._

/**
  * Represents a central location for all Users.
  */
trait UserBase[F[_]] {

  /**
    * Returns the user with the specified username. If the specified username
    * is not found in the database, an attempt will be made to fetch the user
    * from Discourse.
    *
    * @param username Username of user
    * @return User if found, None otherwise
    */
  def withName(username: String)(implicit mdc: OreMDC): OptionT[F, Model[User]]

  /**
    * Returns the requested user when it is the requester or has the requested permission in the orga
    *
    * @param user the requester
    * @param name the requested username
    * @param perm the requested permission
    *
    * @return the requested user
    */
  def requestPermission(user: Model[User], name: String, perm: Permission)(
      implicit mdc: OreMDC
  ): OptionT[F, Model[User]]

  /**
    * Attempts to find the specified User in the database or creates a new User
    * if one does not exist.
    *
    * @param user User to find
    *
    * @return     Found or new User
    */
  def getOrCreate(
      username: String,
      user: User,
      ifInsert: Model[User] => F[Unit]
  ): F[Model[User]]

  /**
    * Creates a new [[Session]] for the specified [[User]].
    *
    * @param user User to create session for
    *
    * @return     Newly created session
    */
  def createSession(user: Model[User]): F[Model[Session]]

  /**
    * Returns the currently authenticated User.c
    *
    * @param session  Current session
    * @return         Authenticated user, if any, None otherwise
    */
  def current(implicit session: Request[_], mdc: OreMDC): OptionT[F, Model[User]]
}

object UserBase {

  /**
    * Default live implementation of [[UserBase]]
    */
  class UserBaseF[F[_]](implicit service: ModelService[F], auth: SpongeAuthApi[F], config: OreConfig, F: Monad[F])
      extends UserBase[F] {

    def withName(username: String)(implicit mdc: OreMDC): OptionT[F, Model[User]] =
      ModelView.now(User).find(equalsIgnoreCase(_.name, username)).orElse {
        EitherT(auth.getUser(username)).map(_.toUser).toOption.semiflatMap(res => service.insert(res))
      }

    def requestPermission(user: Model[User], name: String, perm: Permission)(
        implicit mdc: OreMDC
    ): OptionT[F, Model[User]] = {
      this.withName(name).flatMap { toCheck =>
        if (user == toCheck) OptionT.pure[F](user) // Same user
        else
          toCheck.toMaybeOrganization(ModelView.now(Organization)).flatMap { orga =>
            OptionT.liftF(user.permissionsIn(orga).map(_.has(perm))).collect {
              case true => toCheck // Has Orga perm
            }
          }
      }
    }

    def getOrCreate(
        username: String,
        user: User,
        ifInsert: Model[User] => F[Unit]
    ): F[Model[User]] = {
      def like = ModelView.now(User).find(_.name.toLowerCase === username.toLowerCase)

      like.value.flatMap {
        case Some(u) => F.pure(u)
        case None    => service.insert(user).flatTap(ifInsert)
      }
    }

    def createSession(user: Model[User]): F[Model[Session]] = {
      val maxAge     = config.ore.session.maxAge
      val expiration = OffsetDateTime.now().plus(maxAge.toMillis, ChronoUnit.MILLIS)
      val token      = UUID.randomUUID().toString
      service.insert(Session(expiration, user.id, token))
    }

    /**
      * Returns the [[Session]] of the specified token ID. If the session has
      * expired it will be deleted immediately and None will be returned.
      *
      * @param token  Token of session
      * @return       Session if found and has not expired
      */
    private def getSession(token: String): OptionT[F, Model[Session]] =
      ModelView.now(Session).find(_.token === token).flatMap { session =>
        if (session.hasExpired)
          OptionT(service.delete(session).as(None: Option[Model[Session]]))
        else
          OptionT.some[F](session)
      }

    def current(implicit session: Request[_], mdc: OreMDC): OptionT[F, Model[User]] =
      OptionT
        .fromOption[F](session.cookies.get("_oretoken"))
        .flatMap(cookie => getSession(cookie.value))
        .flatMap(s => ModelView.now(User).get(s.userId))
  }

  def apply[F[_]](implicit userBase: UserBase[F]): UserBase[F] = userBase

  trait UserOrdering
  object UserOrdering {
    val Projects = "projects"
    val UserName = "username"
    val JoinDate = "joined"
    val Role     = "roles"
  }
}
