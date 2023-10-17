package ore.models.admin

import ore.db._
import ore.models.project.{Page, Project, Version}
import ore.models.user.{LoggedActionContext, LoggedActionType, User}

import com.github.tminglei.slickpg.InetString

case class LoggedProject(
    id: Option[DbRef[Project]],
    pluginId: Option[String],
    slug: Option[String],
    ownerName: Option[String]
)
case class LoggedProjectVersion(id: Option[DbRef[Version]], versionString: Option[String])
case class LoggedProjectPage(id: Option[DbRef[Page]], name: Option[String], slug: Option[String])
case class LoggedSubject(id: Option[DbRef[_]], username: Option[String])

case class LoggedActionViewModel[Ctx](
    userId: DbRef[User],
    userName: String,
    address: InetString,
    action: LoggedActionType[Ctx],
    actionContext: LoggedActionContext[Ctx],
    newState: String,
    oldState: String,
    project: LoggedProject,
    version: LoggedProjectVersion,
    page: LoggedProjectPage,
    subject: LoggedSubject
)
