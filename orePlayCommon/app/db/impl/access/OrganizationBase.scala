package db.impl.access

import scala.language.higherKinds

import scala.concurrent.duration._

import ore.OreConfig
import ore.auth.{AuthUser, SpongeAuthApi}
import ore.data.user.notification.NotificationType
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{OrganizationTable, UserTable}
import ore.db.{DbRef, Model, ModelService, ObjId}
import ore.models.organization.Organization
import ore.models.user.role.OrganizationUserRole
import ore.models.user.{Notification, User}
import ore.permission.role.Role
import ore.util.OreMDC
import util.syntax._

import cats.Parallel
import cats.data.{EitherT, NonEmptyList}
import cats.effect.{Sync, Timer}
import cats.syntax.all._
import cats.tagless.autoFunctorK
import com.typesafe.scalalogging
import slick.lifted.TableQuery
import zio.Schedule

@autoFunctorK
trait OrganizationBase[+F[_]] {

  /**
    * Creates a new [[Organization]]. This method creates a new user on the
    * forums to represent the Organization.
    *
    * @param name     Organization name
    * @param ownerId  User ID of the organization owner
    * @return         New organization if successful, None otherwise
    */
  def create(
      name: String,
      ownerId: DbRef[User],
      members: Set[OrganizationUserRole]
  )(implicit mdc: OreMDC): F[Either[List[String], Model[Organization]]]

  /**
    * Returns an [[Organization]] with the specified name if it exists.
    *
    * @param name Organization name
    * @return     Organization with name if exists, None otherwise
    */
  def withName(name: String): F[Option[Model[Organization]]]
}

object OrganizationBase {

  /**
    * Default live implementation of [[OrganizationBase]]
    */
  class OrganizationBaseF[F[_]](
      implicit val service: ModelService[F],
      config: OreConfig,
      auth: SpongeAuthApi[F],
      F: Sync[F],
      par: Parallel[F],
      timer: Timer[F]
  ) extends OrganizationBase[F] {

    private val Logger    = scalalogging.Logger("Organizations")
    private val MDCLogger = scalalogging.Logger.takingImplicit[OreMDC](Logger.underlying)

    def create(
        name: String,
        ownerId: DbRef[User],
        members: Set[OrganizationUserRole]
    )(implicit mdc: OreMDC): F[Either[List[String], Model[Organization]]] = {
      val logging = F.delay {
        MDCLogger.debug("Creating Organization...")
        MDCLogger.debug("Name     : " + name)
        MDCLogger.debug("Owner ID : " + ownerId)
        MDCLogger.debug("Members  : " + members.size)

        // Create the organization as a User on SpongeAuth. This will reserve the
        // name so that no new users or organizations can create an account with
        // that name. We will give the organization a dummy email for continuity.
        // By default we use "<org>@ore.spongepowered.org".
        MDCLogger.debug("Creating on SpongeAuth...")
      }

      // Replace all invalid characters to not throw invalid email error when trying to create org with invalid username
      val dummyEmail   = name.replaceAll("[^a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]", "") + '@' + config.ore.orgs.dummyEmailDomain
      val spongeResult = EitherT.right[List[String]](logging) *> EitherT(auth.createDummyUser(name, dummyEmail))

      def waitTilUserExists(authUser: AuthUser, sleepDur: FiniteDuration, sleptTime: FiniteDuration): F[Boolean] = {
        val hasUser = ModelView.now(User).get(authUser.id).isDefined

        //Do a quick check first if the user is already present
        hasUser.flatMap {
          case true => F.pure(true)
          case false =>
            if (sleptTime > 20.seconds) F.pure(false)
            else timer.sleep(sleepDur) *> waitTilUserExists(authUser, sleepDur * 2, sleptTime + sleepDur * 2)
        }
      }

      // Check for error
      spongeResult
        .leftMap { err =>
          MDCLogger.debug("<FAILURE> " + err)
          err
        }
        .flatMap { spongeUser =>
          EitherT.right[List[String]](waitTilUserExists(spongeUser, 50.millis, 0.millis)).flatMap {
            case true => EitherT.rightT[F, List[String]](spongeUser)
            // Exit early if we never got the user
            case false => EitherT.leftT[F, AuthUser](List("Timed out while waiting for SSO sync"))
          }
        }
        .semiflatMap { spongeUser =>
          MDCLogger.debug("<SUCCESS> " + spongeUser)
          // Next we will create the Organization on Ore itself. This contains a
          // reference to the Sponge user ID, the organization's username and a
          // reference to the User owner of the organization.
          MDCLogger.info("Creating on Ore...")
          service.insert(
            Organization(
              id = ObjId(spongeUser.id),
              userId = spongeUser.id,
              ownerId = ownerId,
              name = spongeUser.username
            )
          )
        }
        .semiflatMap { org =>
          // Every organization model has a regular User companion. Organizations
          // are just normal users with additional information. Adding the
          // Organization global role signifies that this User is an Organization
          // and should be treated as such.
          for {
            userOrg <- org.toUser.getOrElseF(F.raiseError(new IllegalStateException("User not created")))
            _       <- userOrg.globalRoles.addAssoc(Role.Organization.toDbRole.id.value)
            _ <- // Add the owner
            org.memberships.addRole(org)(
              ownerId,
              OrganizationUserRole(
                userId = ownerId,
                organizationId = org.id,
                role = Role.OrganizationOwner,
                isAccepted = true
              )
            )
            _ <- {
              // Invite the User members that the owner selected during creation.
              MDCLogger.debug("Inviting members...")

              members.toVector.parTraverse { role =>
                // TODO remove role.user db access we really only need the userid we already have for notifications
                org.memberships.addRole(org)(role.userId, role.copy(organizationId = org.id)).flatMap { _ =>
                  service.insert(
                    Notification(
                      userId = role.userId,
                      originId = Some(org.id),
                      notificationType = NotificationType.OrganizationInvite,
                      messageArgs = NonEmptyList.of("notification.organization.invite", role.role.title, userOrg.name)
                    )
                  )
                }
              }
            }
          } yield {
            MDCLogger.debug("<SUCCESS> " + org)
            org
          }
        }
        .value
    }

    /**
      * Returns an [[Organization]] with the specified name if it exists.
      *
      * @param name Organization name
      * @return     Organization with name if exists, None otherwise
      */
    def withName(name: String): F[Option[Model[Organization]]] =
      ModelView.now(Organization).find(_.name.toLowerCase === name.toLowerCase).value
  }

  def apply[F[_]](implicit organizationBase: OrganizationBase[F]): OrganizationBase[F] = organizationBase
}
