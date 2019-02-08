package db.impl.access

import java.sql.Timestamp
import java.util.{Date, UUID}

import play.api.mvc.Request

import db.access.ModelView
import db.impl.OrePostgresDriver.api._
import db.{InsertFunc, ModelService}
import models.user.{Organization, Session, User}
import ore.OreConfig
import ore.permission.Permission
import security.spauth.SpongeAuthApi
import util.OreMDC
import util.StringUtils._

import cats.data.OptionT
import cats.effect.{ContextShift, IO}
import cats.syntax.all._

/**
  * Represents a central location for all Users.
  */
class UserBase(implicit val service: ModelService, config: OreConfig) {

  implicit val self: UserBase = this

  /**
    * Returns the user with the specified username. If the specified username
    * is not found in the database, an attempt will be made to fetch the user
    * from Discourse.
    *
    * @param username Username of user
    * @return User if found, None otherwise
    */
  def withName(username: String)(implicit auth: SpongeAuthApi, mdc: OreMDC): OptionT[IO, User] =
    ModelView.now[User].find(equalsIgnoreCase(_.name, username)).orElse {
      auth.getUser(username).map(User.partialFromSponge).semiflatMap(res => service.insert(res))
    }

  /**
    * Returns the requested user when it is the requester or has the requested permission in the orga
    *
    * @param user the requester
    * @param name the requested username
    * @param perm the requested permission
    *
    * @return the requested user
    */
  def requestPermission(user: User, name: String, perm: Permission)(
      implicit auth: SpongeAuthApi,
      cs: ContextShift[IO],
      mdc: OreMDC
  ): OptionT[IO, User] = {
    this.withName(name).flatMap { toCheck =>
      if (user == toCheck) OptionT.pure[IO](user) // Same user
      else
        toCheck.toMaybeOrganization(ModelView.now[Organization]).flatMap { orga =>
          OptionT.liftF(user.can(perm).in(orga)).collect {
            case true => toCheck // Has Orga perm
          }
        }
    }
  }

  /**
    * Attempts to find the specified User in the database or creates a new User
    * if one does not exist.
    *
    * @param user User to find
    *
    * @return     Found or new User
    */
  def getOrCreate(username: String, user: InsertFunc[User], ifInsert: User => IO[Unit] = _ => IO.unit): IO[User] = {
    def like = ModelView.now[User].find(_.name.toLowerCase === username.toLowerCase)

    like.value.flatMap {
      case Some(u) => IO.pure(u)
      case None    => service.insert(user).flatTap(ifInsert)
    }
  }

  /**
    * Creates a new [[Session]] for the specified [[User]].
    *
    * @param user User to create session for
    *
    * @return     Newly created session
    */
  def createSession(user: User): IO[Session] = {
    val maxAge     = this.config.play.sessionMaxAge
    val expiration = new Timestamp(new Date().getTime + maxAge.toMillis)
    val token      = UUID.randomUUID().toString
    service.insert(Session.partial(expiration, user.name, token))
  }

  /**
    * Returns the [[Session]] of the specified token ID. If the session has
    * expired it will be deleted immediately and None will be returned.
    *
    * @param token  Token of session
    * @return       Session if found and has not expired
    */
  private def getSession(token: String): OptionT[IO, Session] =
    ModelView.now[Session].find(_.token === token).flatMap { session =>
      if (session.hasExpired)
        OptionT(service.delete(session).as(None: Option[Session]))
      else
        OptionT.some[IO](session)
    }

  /**
    * Returns the currently authenticated User.c
    *
    * @param session  Current session
    * @return         Authenticated user, if any, None otherwise
    */
  def current(implicit session: Request[_], authApi: SpongeAuthApi, mdc: OreMDC): OptionT[IO, User] =
    OptionT
      .fromOption[IO](session.cookies.get("_oretoken"))
      .flatMap(cookie => getSession(cookie.value))
      .flatMap(_.user)

}

object UserBase {
  def apply()(implicit userBase: UserBase): UserBase = userBase

  implicit def fromService(implicit service: ModelService): UserBase = service.userBase

  trait UserOrdering
  object UserOrdering {
    val Projects = "projects"
    val UserName = "username"
    val JoinDate = "joined"
    val Role     = "roles"
  }
}
