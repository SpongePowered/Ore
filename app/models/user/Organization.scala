package models.user

import java.sql.Timestamp

import com.google.common.base.Preconditions._
import db.impl.OrePostgresDriver.api._
import db.impl.access.UserBase
import db.impl.model.OreModel
import db.impl.table.ModelKeys._
import db.impl.{OrganizationMembersTable, OrganizationRoleTable, OrganizationTable}
import db.{Model, Named}
import models.user.role.OrganizationRole
import ore.organization.OrganizationMember
import ore.permission.role.{Default, RoleTypes, Trust}
import ore.permission.scope.OrganizationScope
import ore.user.{MembershipDossier, UserOwned}
import ore.{Joinable, Visitable}
import slick.lifted.{Compiled, Rep, TableQuery}

import scala.concurrent.{ExecutionContext, Future}

import util.functional.OptionT

/**
  * Represents an Ore Organization. An organization is like a [[User]] in the
  * sense that it shares many qualities with Users and also has a companion
  * User on the forums. An organization is made up by a group of Users who each
  * have a corresponding rank within the organization.
  *
  * @param id             Unique ID
  * @param createdAt      Date of creation
  * @param _ownerId        The ID of the [[User]] that owns this organization
  */
case class Organization(override val id: Option[Int] = None,
                        override val createdAt: Option[Timestamp] = None,
                        username: String,
                        private var _ownerId: Int)
                        extends OreModel(id, createdAt)
                          with UserOwned
                          with OrganizationScope
                          with Named
                          with Visitable
                          with Joinable[OrganizationMember] {

  override type M = Organization
  override type T = OrganizationTable

  /**
    * Contains all information for [[User]] memberships.
    */
  override val memberships: MembershipDossier {
  type MembersTable = OrganizationMembersTable

  type MemberType = OrganizationMember

  type RoleTable = OrganizationRoleTable

  type ModelType = Organization

  type RoleType = OrganizationRole
} = new MembershipDossier {

    type ModelType = Organization
    type RoleType = OrganizationRole
    type RoleTable = OrganizationRoleTable
    type MembersTable = OrganizationMembersTable
    type MemberType = OrganizationMember

    val membersTableClass: Class[MembersTable] = classOf[OrganizationMembersTable]
    val roleClass: Class[RoleType] = classOf[OrganizationRole]
    val model: ModelType = Organization.this

    def newMember(userId: Int)(implicit ec: ExecutionContext) = new OrganizationMember(this.model, userId)

    def clearRoles(user: User): Future[Int] = this.roleAccess.removeAll({ s => (s.userId === user.id.get) && (s.organizationId === id.get) })

    /**
      * Returns the highest level of [[ore.permission.role.Trust]] this user has.
      *
      * @param user User to get trust of
      * @return Trust of user
      */
    override def getTrust(user: User)(implicit ex: ExecutionContext): Future[Trust] = {
      this.userBase.service.DB.db.run(Organization.roleForTrustQuery(id.get, user.id.get).result).map { l =>
        l.sortBy(_.roleType.trust).headOption.map(_.roleType.trust).getOrElse(Default)
      }
    }

  }

  /**
    * Returns the [[User]] that owns this Organization.
    *
    * @return User that owns organization
    */
  override def owner: OrganizationMember = new OrganizationMember(this, this._ownerId)

  override def ownerId: Int = this._ownerId

  override def transferOwner(member: OrganizationMember)(implicit ec: ExecutionContext): Future[Int] = {
    // Down-grade current owner to "Admin"
    for {
      owner <- this.owner.user
      roles <- this.memberships.getRoles(owner)
      memberUser <- member.user
      memberRoles <- this.memberships.getRoles(memberUser)
      setOwner <- this.setOwner(memberUser)
    } yield {
      roles.filter(_.roleType == RoleTypes.OrganizationOwner)
        .foreach(_.setRoleType(RoleTypes.OrganizationAdmin))

      memberRoles.foreach(_.setRoleType(RoleTypes.OrganizationOwner))

      setOwner
    }
  }


  /**
    * Sets the [[User]] that owns this Organization.
    *
    * @param user User that owns this organization
    */
  def setOwner(user: User): Future[Int] = {
    checkNotNull(user, "null user", "")
    checkArgument(user.isDefined, "undefined user", "")
    this._ownerId = user.id.get
    if (isDefined) {
      update(OrgOwnerId)
    } else Future.successful(0)
  }

  /**
    * Returns this Organization as a [[User]].
    *
    * @return This Organization as a User
    */
  def toUser(implicit ec: ExecutionContext): OptionT[Future, User] = this.service.getModelBase(classOf[UserBase]).withName(this.username)

  override val name: String = this.username
  override def url: String = this.username
  override val userId: Int = this._ownerId
  override def organizationId: Int = this.id.get
  override def copyWith(id: Option[Int], theTime: Option[Timestamp]): Model = this.copy(createdAt = theTime)

}

object Organization {
  lazy val roleForTrustQuery = Compiled(queryRoleForTrust _)

  private def queryRoleForTrust(orgId: Rep[Int], userId: Rep[Int]) = {
    val memberTable = TableQuery[OrganizationMembersTable]
    val roleTable = TableQuery[OrganizationRoleTable]

    for {
      m <- memberTable if m.organizationId === orgId && m.userId === userId
      r <- roleTable if m.userId === r.userId && r.organizationId === orgId
    } yield {
      r
    }
  }
}
