package ore.project

import scala.concurrent.{ExecutionContext, Future}

import db.{ModelService, ObjectReference}
import models.project.Project
import models.user.role.ProjectUserRole
import ore.user.{Member, UserOwned}

/**
  * Represents a member of a [[Project]].
  *
  * @param project  Project this Member is a part of
  * @param userId   Member user ID
  */
class ProjectMember(val project: Project, val userId: ObjectReference) extends Member[ProjectUserRole] {

  override def roles(implicit ec: ExecutionContext, service: ModelService): Future[Set[ProjectUserRole]] =
    UserOwned[ProjectMember].user(this).flatMap(user => this.project.memberships.getRoles(project, user))

  /**
    * Returns the Member's top role.
    *
    * @return Top role
    */
  override def headRole(implicit ec: ExecutionContext, service: ModelService): Future[ProjectUserRole] =
    this.roles.map(_.maxBy(_.role.trust))
}
object ProjectMember {
  implicit val isUserOwned: UserOwned[ProjectMember] = (a: ProjectMember) => a.userId
}
