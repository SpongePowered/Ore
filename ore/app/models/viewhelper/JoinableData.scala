package models.viewhelper

import controllers.sugar.Requests.OreRequest
import ore.models.user.User
import ore.models.user.role.UserRoleModel
import ore.Joinable
import ore.db.Model
import ore.permission.Permission
import ore.permission.role.RoleCategory

trait JoinableData[R <: UserRoleModel[R], T <: Joinable] {

  def joinable: Model[T]
  def members: Seq[(Model[R], Model[User])]

  def roleCategory: RoleCategory

  def filteredMembers(implicit request: OreRequest[_]): Seq[(Model[R], Model[User])] = {
    val hasEditMembers = request.headerData.globalPerm(Permission.ManageSubjectMembers)
    val userIsOwner    = request.currentUser.map(_.id.value).contains(joinable.ownerId)
    if (hasEditMembers || userIsOwner)
      members
    else
      members.filter {
        case (role, _) => role.isAccepted // project role is accepted
      }
  }
}
