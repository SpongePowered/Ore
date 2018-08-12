package ore.permission.role

import db.impl.OrePostgresDriver
import db.table.MappedType
import models.user.role.{OrganizationRole, ProjectRole}
import ore.Colors._
import slick.jdbc.JdbcType

import scala.language.implicitConversions

/**
  * Represents a collection of roles a User may have.
  */
object RoleTypes extends Enumeration {

  // Global

  val Admin           = new  RoleType( 0, "Ore_Admin",         61, classOf[GlobalRole], Absolute,   "Ore Admin",          Red)
  val Mod             = new  RoleType( 1, "Ore_Mod",           62, classOf[GlobalRole], Moderation, "Ore Moderator",      Aqua)
  val SpongeLeader    = new  RoleType( 2, "Sponge_Leader",     44, classOf[GlobalRole], Default,    "Sponge Leader",      Amber)
  val TeamLeader      = new  RoleType( 3, "Team_Leader",       58, classOf[GlobalRole], Default,    "Team Leader",        Amber)
  val CommunityLeader = new  RoleType( 4, "Community_Leader",  59, classOf[GlobalRole], Default,    "Community Leader",   Amber)
  val Staff           = new  RoleType( 5, "Sponge_Staff",      3,  classOf[GlobalRole], Default,    "Sponge Staff",       Amber)
  val SpongeDev       = new  RoleType( 6, "Sponge_Developer",  41, classOf[GlobalRole], Default,    "Sponge Developer",   Green)
  val OreDev          = new  RoleType(27, "Ore_Dev",           66, classOf[GlobalRole], Default,    "Ore Developer",      Orange)
  val WebDev          = new  RoleType( 7, "Web_dev",           45, classOf[GlobalRole], Default,    "Web Developer",      Blue)
  val Documenter      = new  RoleType( 8, "Documenter",        51, classOf[GlobalRole], Default,    "Documenter",         Aqua)
  val Support         = new  RoleType( 9, "Support",           43, classOf[GlobalRole], Default,    "Support",            Aqua)
  val Contributor     = new  RoleType(10, "Contributor",       49, classOf[GlobalRole], Default,    "Contributor",        Green)
  val Advisor         = new  RoleType(11, "Advisor",           48, classOf[GlobalRole], Default,    "Advisor",            Aqua)

  val StoneDonor   = new DonorType(12, "Stone_Donor",  57, "Stone Donor",   Gray)
  val QuartzDonor  = new DonorType(13, "Quartz_Donor", 54, "Quartz Donor",  Quartz)
  val IronDonor    = new DonorType(14, "Iron_Donor",   56, "Iron Donor",    Silver)
  val GoldDonor    = new DonorType(15, "Gold_Donor",   53, "Gold Donor",    Gold)
  val DiamondDonor = new DonorType(16, "Diamond_Donor",52, "Diamond Donor", LightBlue)

  // Project

  val ProjectOwner   = new RoleType(17, "Project_Owner",    -1, classOf[ProjectRole], Absolute, "Owner",    Transparent, isAssignable = false)
  val ProjectDev     = new RoleType(18, "Project_Developer",-2, classOf[ProjectRole], Publish,   "Developer",Transparent)
  val ProjectEditor  = new RoleType(19, "Project_Editor",   -3, classOf[ProjectRole], Limited,  "Editor",   Transparent)
  val ProjectSupport = new RoleType(20, "Project_Support",  -4, classOf[ProjectRole], Default,  "Support",  Transparent)

  // Organization

  val Organization        = new RoleType(21, "Organization",             64, classOf[OrganizationRole], Absolute, "Organization", Purple, isAssignable = false)
  val OrganizationOwner   = new RoleType(22, "Organization_Owner",       -5, classOf[OrganizationRole], Absolute, "Owner",        Purple, isAssignable = false)
  val OrganizationAdmin   = new RoleType(26, "Organization_Admin",       -9, classOf[OrganizationRole], Lifted,   "Admin",        Purple)
  val OrganizationDev     = new RoleType(23, "Organization_Developer",   -6, classOf[OrganizationRole], Publish,  "Developer",    Transparent)
  val OrganizationEditor  = new RoleType(24, "Organization_Editor",      -7, classOf[OrganizationRole], Limited,  "Editor",       Transparent)
  val OrganizationSupport = new RoleType(25, "Organization_Support",     -8, classOf[OrganizationRole], Default,  "Support",      Transparent)

  /**
    * Returns the role with the specified external ID.
    *
    * @param id Sponge ID
    * @return   UserRole with ID
    */
  def withId(id: Int): RoleType = this.values.find(_.roleId == id).getOrElse {
    // Throw an exception instead of returning an Option to match Enumeration
    // behavior
    throw new NoSuchElementException
  }

  /**
    * Returns the role with the specified internal name.
    *
    * @param internalName Internal name
    * @return UserRole with specified internal name
    */
  def withInternalName(internalName: String): Option[RoleType] = this.values.find(_.internalName == internalName).map(value => convert(value))

  /**
    * Represents a User role.
    *
    * @param i      Index
    * @param roleId ID of role
    * @param trust  Level of trust that this user has
    * @param title  Title to display
    * @param color  Color to display
    */
  class RoleType(val i: Int,
                 val internalName: String,
                 val roleId: Int,
                 val roleClass: Class[_ <: Role],
                 val trust: Trust,
                 val title: String,
                 val color: Color,
                 val isAssignable: Boolean = true)
                 extends super.Val(i, title) with MappedType[RoleType] {
    implicit val mapper: JdbcType[RoleType] = OrePostgresDriver.api.roleTypeTypeMapper
  }

  /**
    * Represents a type of Donor.
    *
    * @param i      Index
    * @param roleId ID of role
    * @param title  Title to display
    * @param color  Color to display
    */
  class DonorType(override val i: Int,
                  override val internalName: String,
                  override val roleId: Int,
                  override val title: String,
                  override val color: Color)
                  extends RoleType(i, internalName, roleId, classOf[GlobalRole], Default, title, color)

  implicit def convert(value: Value): RoleType = value.asInstanceOf[RoleType]

}
