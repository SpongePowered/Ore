package ore.permission

import scala.collection.immutable

import enumeratum._

sealed abstract class NamedPermission(val permission: Permission) extends EnumEntry with EnumEntry.Snakecase
object NamedPermission extends Enum[NamedPermission] {
  case object ViewPublicInfo      extends NamedPermission(Permission.ViewPublicInfo)
  case object EditOwnUserSettings extends NamedPermission(Permission.EditOwnUserSettings)
  case object EditApiKeys         extends NamedPermission(Permission.EditApiKeys)

  case object EditSubjectSettings  extends NamedPermission(Permission.EditSubjectSettings)
  case object ManageSubjectMembers extends NamedPermission(Permission.ManageSubjectMembers)
  case object IsSubjectOwner       extends NamedPermission(Permission.IsSubjectOwner)
  case object IsSubjectMember      extends NamedPermission(Permission.IsSubjectMember)

  case object CreateProject extends NamedPermission(Permission.CreateProject)
  case object EditPage      extends NamedPermission(Permission.EditPage)
  case object DeleteProject extends NamedPermission(Permission.DeleteProject)

  case object CreateVersion extends NamedPermission(Permission.CreateVersion)
  case object EditVersion   extends NamedPermission(Permission.EditVersion)
  case object DeleteVersion extends NamedPermission(Permission.DeleteVersion)
  case object EditTags      extends NamedPermission(Permission.EditChannel)

  case object CreateOrganization extends NamedPermission(Permission.CreateOrganization)
  case object PostAsOrganization extends NamedPermission(Permission.PostAsOrganization)

  case object ModNotesAndFlags extends NamedPermission(Permission.ModNotesAndFlags)
  case object SeeHidden        extends NamedPermission(Permission.SeeHidden)
  case object IsStaff          extends NamedPermission(Permission.IsStaff)
  case object Reviewer         extends NamedPermission(Permission.Reviewer)

  case object ViewHealth extends NamedPermission(Permission.ViewHealth)
  case object ViewIp     extends NamedPermission(Permission.ViewIp)
  case object ViewStats  extends NamedPermission(Permission.ViewStats)
  case object ViewLogs   extends NamedPermission(Permission.ViewLogs)

  case object ManualValueChanges extends NamedPermission(Permission.ManualValueChanges)
  case object HardDeleteProject  extends NamedPermission(Permission.HardDeleteProject)
  case object HardDeleteVersion  extends NamedPermission(Permission.HardDeleteVersion)
  case object EditAdminSettings  extends NamedPermission(Permission.EditAdminSettings)

  override def values: immutable.IndexedSeq[NamedPermission] = findValues

  def parseNamed(names: Seq[String]): Option[Vector[NamedPermission]] = {
    import cats.syntax.all._
    names.toVector.traverse(NamedPermission.withNameOption)
  }
}
