package controllers.sugar

import play.api.mvc.Call

import ore.models.project.Project
import ore.models.user.User

/**
  * Helper class for commonly used calls throughout the application.
  */
trait Calls {

  /**
    * A call to the home page.
    */
  val ShowHome: Call = controllers.routes.Application.showHome()

  /**
    * A call to a [[User]] page.
    *
    * @param username Username of user
    * @return         Call to user page
    */
  def ShowUser(username: String): Call = controllers.routes.Users.showProjects(username)

  /**
    * A call to a [[User]] page.
    *
    * @param user User to show
    * @return     Call to user page
    */
  def ShowUser(user: User): Call = ShowUser(user.name)

  /**
    * A call to a [[Project]] page.
    *
    * @param author Project owner
    * @param slug   Project slug
    * @return       Call to project page
    */
  def ShowProject(author: String, slug: String): Call = controllers.project.routes.Projects.show(author, slug, "")

  def showVersion(author: String, slug: String, version: String): Call =
    controllers.project.routes.Projects.show(author, slug, s"versions/$version")

  def showPage(author: String, slug: String, page: String): Call =
    controllers.project.routes.Projects.show(author, slug, s"pages/$page")
}
object Calls extends Calls
