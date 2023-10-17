package ore.permission.role

import java.time.OffsetDateTime

import scala.collection.immutable

import ore.data.Color
import ore.data.Color._
import ore.db.{Model, ObjId, ObjOffsetDateTime}
import ore.models.user.role.DbRole
import ore.permission.{Permission => Perm}

import enumeratum.values.{StringEnum, StringEnumEntry}

sealed abstract case class Role(
    value: String,
    roleId: Int,
    category: RoleCategory,
    permissions: Perm,
    title: String,
    color: Color,
    isAssignable: Boolean = true
) extends StringEnumEntry {

  def toDbRole: Model[DbRole] =
    DbRole.asDbModel(
      DbRole(
        name = value,
        category = category,
        permissions = permissions,
        title = title,
        color = color.hex,
        isAssignable = isAssignable,
        rank = rankOpt
      ),
      ObjId(roleId.toLong),
      ObjOffsetDateTime(OffsetDateTime.MIN)
    )

  def rankOpt: Option[Int] = None
}

sealed abstract class DonorRole(
    override val value: String,
    override val roleId: Int,
    override val title: String,
    override val color: Color,
    val rank: Int
) extends Role(value, roleId, RoleCategory.Global, Perm.None, title, color) {

  override def rankOpt: Option[Int] = Some(rank)
}

object Role extends StringEnum[Role] {
  lazy val byIds: Map[Int, Role] = values.map(r => r.roleId -> r).toMap

  object OreAdmin extends Role("Ore_Admin", 1, RoleCategory.Global, Perm.All, "Ore Admin", Red)
  object OreMod
      extends Role(
        "Ore_Mod",
        2,
        RoleCategory.Global,
        Perm(
          Perm.IsStaff,
          Perm.Reviewer,
          Perm.ModNotesAndFlags,
          Perm.SeeHidden
        ),
        "Ore Moderator",
        Aqua
      )
  object SpongeLeader    extends Role("Sponge_Leader", 3, RoleCategory.Global, Perm.None, "Sponge Leader", Amber)
  object TeamLeader      extends Role("Team_Leader", 4, RoleCategory.Global, Perm.None, "Team Leader", Amber)
  object CommunityLeader extends Role("Community_Leader", 5, RoleCategory.Global, Perm.None, "Community Leader", Amber)
  object SpongeStaff
      extends Role(
        "Sponge_Staff",
        6,
        RoleCategory.Global,
        Perm.None,
        "Sponge Staff",
        Amber
      )
  object SpongeDev extends Role("Sponge_Developer", 7, RoleCategory.Global, Perm.None, "Sponge Developer", Green)
  object OreDev
      extends Role(
        "Ore_Dev",
        8,
        RoleCategory.Global,
        Perm(Perm.ViewStats, Perm.ViewLogs, Perm.ViewHealth, Perm.ManualValueChanges),
        "Ore Developer",
        Orange
      )
  object WebDev
      extends Role("Web_Dev", 9, RoleCategory.Global, Perm(Perm.ViewLogs, Perm.ViewHealth), "Web Developer", Blue)
  object Documenter  extends Role("Documenter", 10, RoleCategory.Global, Perm.None, "Documenter", Aqua)
  object Support     extends Role("Support", 11, RoleCategory.Global, Perm.None, "Support", Aqua)
  object Contributor extends Role("Contributor", 12, RoleCategory.Global, Perm.None, "Contributor", Green)
  object Advisor     extends Role("Advisor", 13, RoleCategory.Global, Perm.None, "Advisor", Aqua)

  object StoneDonor   extends DonorRole("Stone_Donor", 14, "Stone Donor", Gray, 5)
  object QuartzDonor  extends DonorRole("Quartz_Donor", 15, "Quartz Donor", Quartz, 4)
  object IronDonor    extends DonorRole("Iron_Donor", 16, "Iron Donor", Silver, 3)
  object GoldDonor    extends DonorRole("Gold_Donor", 17, "Gold Donor", Gold, 2)
  object DiamondDonor extends DonorRole("Diamond_Donor", 18, "Diamond Donor", LightBlue, 1)

  object ProjectOwner
      extends Role(
        "Project_Owner",
        19,
        RoleCategory.Project,
        Perm(
          Perm.IsProjectOwner,
          Perm.DeleteProject,
          ProjectAdmin.permissions
        ),
        "Owner",
        Transparent,
        isAssignable = false
      )
  object ProjectAdmin
      extends Role(
        "Project_Admin",
        29,
        RoleCategory.Project,
        Perm(
          Perm.EditProjectSettings,
          Perm.ManageProjectMembers,
          Perm.EditApiKeys,
          Perm.DeleteVersion,
          ProjectDeveloper.permissions
        ),
        "Admin",
        Transparent
      )
  object ProjectDeveloper
      extends Role(
        "Project_Developer",
        20,
        RoleCategory.Project,
        Perm(Perm.CreateVersion, Perm.EditVersion, Perm.EditChannel, ProjectEditor.permissions),
        "Developer",
        Transparent
      )
  object ProjectEditor
      extends Role(
        "Project_Editor",
        21,
        RoleCategory.Project,
        Perm(Perm.EditPage, ProjectSupport.permissions),
        "Editor",
        Transparent
      )
  object ProjectSupport
      extends Role("Project_Support", 22, RoleCategory.Project, Perm.IsProjectMember, "Support", Transparent)

  object Organization
      extends Role(
        "Organization",
        23,
        RoleCategory.Organization,
        OrganizationOwner.permissions,
        "Organization",
        Purple,
        isAssignable = false
      )
  object OrganizationOwner
      extends Role(
        "Organization_Owner",
        24,
        RoleCategory.Organization,
        Perm(Perm.IsOrganizationOwner, ProjectOwner.permissions, OrganizationAdmin.permissions),
        "Owner",
        Purple,
        isAssignable = false
      )
  object OrganizationAdmin
      extends Role(
        "Organization_Admin",
        25,
        RoleCategory.Organization,
        Perm(
          Perm.EditApiKeys,
          Perm.ManageProjectMembers,
          Perm.EditOwnUserSettings,
          Perm.DeleteProject,
          Perm.DeleteVersion,
          OrganizationDev.permissions
        ),
        "Admin",
        Transparent
      )
  object OrganizationDev
      extends Role(
        "Organization_Developer",
        26,
        RoleCategory.Organization,
        Perm(
          Perm.CreateProject,
          Perm.EditProjectSettings,
          ProjectDeveloper.permissions,
          OrganizationEditor.permissions
        ),
        "Developer",
        Transparent
      )
  object OrganizationEditor
      extends Role(
        "Organization_Editor",
        27,
        RoleCategory.Organization,
        Perm(ProjectEditor.permissions, OrganizationSupport.permissions),
        "Editor",
        Transparent
      )
  object OrganizationSupport
      extends Role(
        "Organization_Support",
        28,
        RoleCategory.Organization,
        Perm(Perm.PostAsOrganization, Perm.IsOrganizationMember),
        "Support",
        Transparent
      )

  lazy val values: immutable.IndexedSeq[Role] = findValues

  val projectRoles: immutable.IndexedSeq[Role] = values.filter(_.category == RoleCategory.Project)

  val organizationRoles: immutable.IndexedSeq[Role] = values.filter(_.category == RoleCategory.Organization)
}

sealed abstract class RoleCategory(val value: String) extends StringEnumEntry
object RoleCategory extends StringEnum[RoleCategory] {
  override def values: immutable.IndexedSeq[RoleCategory] = findValues

  case object Global       extends RoleCategory("global")
  case object Project      extends RoleCategory("project")
  case object Organization extends RoleCategory("organization")
}
