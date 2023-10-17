package ore.rest

import java.lang.Math._

import scala.annotation.unused

import play.api.libs.json.Json.{obj, toJson}
import play.api.libs.json.{JsArray, JsObject, JsString, JsValue}

import db.impl.access.ProjectBase
import db.impl.query.AppQueries
import ore.OreConfig
import ore.data.project.Category
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema._
import ore.db.{DbRef, Model, ModelService}
import ore.models.project.{ProjectSortingStrategy, _}
import ore.models.user.User
import ore.models.user.role.ProjectUserRole
import ore.permission.role.Role
import util.syntax._

import cats.data.OptionT
import zio.interop.catz._
import zio.{UIO, ZIO}

/**
  * The Ore API
  */
trait OreRestfulApiV1 extends OreWrites {

  implicit def service: ModelService[UIO]
  def config: OreConfig

  /**
    * Returns a Json value of the Projects meeting the specified criteria.
    *
    * @param categories Project categories
    * @param sort       Ordering
    * @param q          Query string
    * @param limit      Amount to take
    * @param offset     Amount to drop
    * @return           JSON list of projects
    */
  def getProjectList(
      categories: Option[String],
      sort: Option[Int],
      q: Option[String],
      limit: Option[Long],
      offset: Option[Long]
  ): UIO[JsValue] = {
    val cats: Option[Seq[Category]] = categories.map(Category.fromString).map(_.toSeq)
    val ordering                    = sort.map(ProjectSortingStrategy.withValue).getOrElse(ProjectSortingStrategy.Default)

    val maxLoad = this.config.ore.projects.initLoad
    val lim     = max(min(limit.getOrElse(maxLoad), maxLoad), 0)

    for {
      preSearch <- service.runDbCon(
        AppQueries
          .apiV1IdSearch(q, cats.fold(Nil: List[Category])(_.toList), ordering, lim.toInt, offset.getOrElse(0L).toInt)
          .to[Vector]
      )
      unsortedProjects <- service.runDBIO(queryProjectRV.filter(_._1.id.inSetBind(preSearch)).result)
      sortedProjects = for {
        id <- preSearch
        t  <- unsortedProjects
        if t._1.id.value == id
        (p, v) = t
      } yield (p, v, FakeChannel.fromVersion(v))
      json <- writeProjects(sortedProjects)
    } yield {
      toJson(json.map(_._2))
    }
  }

  private def getMembers(projects: Seq[DbRef[Project]]) =
    for {
      r <- TableQuery[ProjectRoleTable] if r.isAccepted === true && r.projectId.inSetBind(projects)
      u <- TableQuery[UserTable] if r.userId === u.id
    } yield (r, u)

  def writeMembers(members: Seq[(Model[ProjectUserRole], Model[User])]): Seq[JsObject] = {
    val allRoles = members.groupBy(_._1.userId).view.mapValues(_.map(_._1.role))
    members.map {
      case (_, user) =>
        val roles                      = allRoles(user.id)
        val trustOrder: Ordering[Role] = Ordering.by(_.permissions: Long) //This is terrible, but probably works
        obj(
          "userId"   -> user.id.value,
          "name"     -> user.name,
          "roles"    -> JsArray(roles.map(role => JsString(role.title))),
          "headRole" -> roles.max(trustOrder).title
        )
    }
  }

  private def writeProjects(
      projects: Seq[(Model[Project], Model[Version], FakeChannel)]
  ): UIO[Seq[(Model[Project], JsObject)]] = {
    val projectIds = projects.map(_._1.id.value)

    for {
      members <- service.runDBIO(getMembers(projectIds).result).map(_.groupBy(_._1.projectId))
    } yield {

      projects.map {
        case (p, v, c) =>
          (
            p,
            obj(
              "pluginId"    -> p.pluginId,
              "createdAt"   -> p.createdAt.toString,
              "name"        -> p.name,
              "owner"       -> p.ownerName,
              "description" -> p.description,
              "href"        -> s"/${p.ownerName}/${p.slug}",
              "members"     -> writeMembers(members.getOrElse(p.id.value, Seq.empty)),
              "channels"    -> toJson(Version.Stability.values.map(_.value.capitalize)),
              "recommended" -> toJson(writeVersion(v, p, c, None)),
              "category"    -> obj("title" -> p.category.title, "icon" -> p.category.icon),
              "views"       -> 0,
              "downloads"   -> 0,
              "stars"       -> 0
            )
          )
      }
    }
  }

  def writeVersion(
      v: Model[Version],
      p: Project,
      c: FakeChannel,
      author: Option[String]
  ): JsObject = {
    val dependencies: List[JsObject] = v.dependencies.map { dependency =>
      obj("pluginId" -> dependency.pluginId, "version" -> dependency.version)
    }
    val json = obj(
      "id"            -> v.id.value,
      "createdAt"     -> v.createdAt.toString,
      "name"          -> v.versionString,
      "dependencies"  -> dependencies,
      "pluginId"      -> p.pluginId,
      "channel"       -> toJson(c),
      "fileSize"      -> v.fileSize,
      "md5"           -> v.hash,
      "staffApproved" -> v.reviewState.isChecked,
      "reviewState"   -> v.reviewState.toString,
      "href"          -> ("/" + v.url(p)),
      "tags"          -> JsArray.empty,
      "downloads"     -> 0,
      "description"   -> v.description
    )

    author.fold(json)(a => json + (("author", JsString(a))))
  }

  private def queryProjectRV = {
    for {
      hp <- TableQuery[ApiV1ProjectsTable]
      p  <- TableQuery[ProjectTable] if hp.id === p.id
      v  <- TableQuery[VersionTable]
      if p.id === v.projectId && v.versionString === ((hp.promotedVersions ~> 0) +>> "version_string")
      if Visibility.isPublicFilter[ProjectTable](p)
    } yield (p, v)
  }

  /**
    * Returns a Json value of the Project with the specified ID.
    *
    * @param pluginId Project plugin ID
    * @return Json value of project if found, None otherwise
    */
  def getProject(pluginId: String): UIO[Option[JsValue]] = {
    val query = queryProjectRV.filter {
      case (p, _) => p.pluginId === pluginId
    }
    for {
      project <- service.runDBIO(query.result.headOption)
      json    <- writeProjects(project.map(t => (t._1, t._2, FakeChannel.fromVersion(t._2))).toSeq)
    } yield {
      json.headOption.map(_._2)
    }
  }

  /**
    * Returns a Json value of the Versions meeting the specified criteria.
    *
    * @param pluginId Project plugin ID
    * @param channels Version channels
    * @param limit    Amount to take
    * @param offset   Amount to drop
    * @return         JSON list of versions
    */
  def getVersionList(
      pluginId: String,
      channels: Option[String],
      limit: Option[Int],
      offset: Option[Int],
      onlyPublic: Boolean
  ): UIO[JsValue] = {
    val stabilityFilter = channels.map(_.split(",").view.flatMap(Version.Stability.withValueOpt).toSeq)

    val filtered = stabilityFilter
      .map { stabilities =>
        queryVersions(onlyPublic).filter {
          case (_, v, _, _) =>
            // Only allow versions in the specified channels or all if none specified
            v.stability.inSetBind(stabilities)
        }
      }
      .getOrElse(queryVersions(onlyPublic))
      .filter { case (p, _, _, _) => p.pluginId.toLowerCase === pluginId.toLowerCase }
      .sortBy { case (_, v, _, _) => v.createdAt.desc }

    val maxLoad = this.config.ore.projects.initVersionLoad
    val lim     = max(min(limit.getOrElse(maxLoad), maxLoad), 0)

    val limited = filtered.drop(offset.getOrElse(0)).take(lim)

    for {
      data <- service.runDBIO(limited.result) // Get Project Version Channel and AuthorName
    } yield {
      val list = data.map {
        case (p, v, _, uName) =>
          writeVersion(v, p, FakeChannel.fromVersion(v), uName)
      }
      toJson(list)
    }
  }

  /**
    * Returns a Json value of the specified version.
    *
    * @param pluginId Project plugin ID
    * @param name     Version name
    * @return         JSON version if found, None otherwise
    */
  def getVersion(pluginId: String, name: String): UIO[Option[JsValue]] = {

    val filtered = queryVersions().filter {
      case (p, v, _, _) =>
        p.pluginId.toLowerCase === pluginId.toLowerCase &&
          v.versionString.toLowerCase === name.toLowerCase
    }

    for {
      data <- service.runDBIO(filtered.result.headOption) // Get Project Version Channel and AuthorName
    } yield {
      data.map {
        case (p, v, _, uName) =>
          writeVersion(v, p, FakeChannel.fromVersion(v), uName)
      }
    }
  }

  private def queryVersions(onlyPublic: Boolean = true) =
    for {
      p      <- TableQuery[ProjectTable]
      (v, u) <- TableQuery[VersionTable].joinLeft(TableQuery[UserTable]).on(_.authorId === _.id)
      if p.id === v.projectId && (if (onlyPublic)
                                    v.visibility === (Visibility.Public: Visibility)
                                  else true)
    } yield (p, v, v.id, u.map(_.name))

  /**
    * Returns a list of pages for the specified project.
    *
    * @param pluginId Project plugin ID
    * @param parentId Optional parent ID filter
    * @return         List of project pages
    */
  def getPages(
      pluginId: String,
      parentId: Option[DbRef[Page]]
  )(implicit projectBase: ProjectBase[UIO]): OptionT[UIO, JsValue] = {
    OptionT(projectBase.withPluginId(pluginId)).semiflatMap { project =>
      for {
        pages <- service.runDBIO(project.pages(ModelView.raw(Page)).sortBy(_.name).result)
      } yield {
        val seq      = pages.filter(_.parentId == parentId)
        val pageById = pages.map(p => (p.id.value, p)).toMap
        toJson(
          seq.map(page =>
            obj(
              "createdAt" -> page.createdAt.value,
              "id"        -> page.id.value,
              "name"      -> page.name,
              "parentId"  -> page.parentId,
              "slug"      -> page.slug,
              "fullSlug"  -> page.fullSlug(page.parentId.flatMap(pageById.get).map(_.obj))
            )
          )
        )
      }
    }
  }

  private def queryStars(users: Seq[Model[User]]) =
    for {
      s <- TableQuery[ProjectStarsTable] if s.userId.inSetBind(users.map(_.id.value))
      p <- TableQuery[ProjectTable] if s.projectId === p.id
    } yield (s.userId, p.pluginId)

  /**
    * Returns a Json value of Users.
    *
    * @param limit  Amount to take
    * @param offset Amount to drop
    * @return       List of users
    */
  def getUserList(
      limit: Option[Int],
      offset: Option[Int]
  ): UIO[JsValue] =
    for {
      users        <- service.runDBIO(TableQuery[UserTable].drop(offset.getOrElse(0)).take(limit.getOrElse(25)).result)
      writtenUsers <- writeUsers(users)
    } yield toJson(writtenUsers)

  def writeUsers(
      userList: Seq[Model[User]]
  ): UIO[Seq[JsObject]] = {
    implicit def config: OreConfig = this.config

    val query = queryProjectRV.filter {
      case (p, _) => p.ownerId.inSetBind(userList.map(_.id.value)) // query all projects with given users
    }

    for {
      allProjects     <- service.runDBIO(query.result)
      stars           <- service.runDBIO(queryStars(userList).result).map(_.groupBy(_._1).view.mapValues(_.map(_._2)))
      jsonProjects    <- writeProjects(allProjects.map(t => (t._1, t._2, FakeChannel.fromVersion(t._2))))
      userGlobalRoles <- ZIO.foreachParN(config.performance.nioBlockingFibers)(userList)(_.globalRoles.allFromParent)
    } yield {
      val projectsByUser = jsonProjects.groupBy(_._1.ownerId).view.mapValues(_.map(_._2))
      userList.zip(userGlobalRoles).map {
        case (user, globalRoles) =>
          obj(
            "id"        -> user.id.value,
            "createdAt" -> user.createdAt.toString,
            "username"  -> user.name,
            "roles"     -> globalRoles.map(_.title),
            "starred"   -> toJson(stars.getOrElse(user.id.value, Seq.empty)),
            "avatarUrl" -> user.avatarUrl,
            "projects"  -> toJson(projectsByUser.getOrElse(user.id.value, Nil))
          )
      }
    }
  }

  /**
    * Returns a Json value of the User with the specified username.
    *
    * @param username Username of User
    * @return         JSON user if found, None otherwise
    */
  def getUser(username: String): UIO[Option[JsValue]] = {
    val queryOneUser = TableQuery[UserTable].filter {
      _.name.toLowerCase === username.toLowerCase
    }

    for {
      user <- service.runDBIO(queryOneUser.result)
      json <- writeUsers(user)
    } yield json.headOption
  }

  /**
    * Returns a Json array of the tags on a project's version
    *
    * @param pluginId Project plugin ID
    * @param version  Version name
    * @return         Tags on the Version
    */
  def getTags(
      pluginId: String,
      @unused version: String
  )(implicit projectBase: ProjectBase[UIO]): OptionT[UIO, JsValue] =
    OptionT(projectBase.withPluginId(pluginId)).map(_ => JsArray.empty)

  /**
    * Get the Tag Color information from an ID
    *
    * @param tagId The ID of the Tag Color
    * @return The Tag Color
    */
  def getTagColor(tagId: Int): Option[JsValue] = TagColor.withValueOpt(tagId).map(toJson(_)(tagColorWrites))
}

class OreRestfulServerV1(val service: ModelService[UIO], val config: OreConfig) extends OreRestfulApiV1
