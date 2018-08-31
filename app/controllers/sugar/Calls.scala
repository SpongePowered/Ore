package controllers.sugar

import controllers.routes
import models.project.Project
import models.user.User
import play.api.mvc.Call

/**
  * Helper class for commonly used calls throughout the application.
  */
trait Calls {

  /**
    * A call to the home page.
    */
  val ShowHome: Call = routes.Application.showHome(None, None, None, None, None, None)

  /**
    * A call to a [[User]] page.
    *
    * @param username Username of user
    * @return         Call to user page
    */
  def ShowUser(username: String): Call = routes.Users.showProjects(username, None)

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
  def ShowProject(author: String, slug: String): Call = controllers.project.routes.Projects.show(author, slug)

  def ShowProject(pluginId: String): Call = controllers.project.routes.Projects.showProjectById(pluginId)

  /**
    * A call to a [[Project]] page.
    *
    * @param project  Project to show
    * @return         Project page
    */
  def ShowProject(project: Project): Call = ShowProject(project.ownerName, project.slug)

}
