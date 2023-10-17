package ore.models.project

import scala.language.higherKinds

import ore.db.access.{ModelView, QueryView}
import ore.db.impl.DefaultModelCompanion
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.common.Named
import ore.db.impl.schema.{PageTable, ProjectTable}
import ore.db.{DbRef, Model, ModelQuery}
import ore.syntax._
import ore.util.StringUtils._

import slick.lifted.TableQuery

/**
  * Represents a documentation page within a project.
  *
  * @param projectId    Project ID
  * @param parentId     The parent page ID, -1 if none
  * @param name         Page name
  * @param slug         Page URL slug
  * @param contents    Markdown contents
  * @param isDeletable  True if can be deleted by the user
  */
case class Page private (
    projectId: DbRef[Project],
    parentId: Option[DbRef[Page]],
    name: String,
    slug: String,
    isDeletable: Boolean,
    contents: Option[String]
) extends Named {

  /**
    * Get Project associated with page.
    *
    * @return Optional Project
    */
  def parentProject[QOptRet, SRet[_]](view: ModelView[QOptRet, SRet, ProjectTable, Model[Project]]): QOptRet =
    view.get(projectId)

  def parentPage[QOptRet, SRet[_]](view: ModelView[QOptRet, SRet, PageTable, Model[Page]]): Option[QOptRet] =
    parentId.map(view.get)

  /**
    * Get the /:parent/:child
    *
    * @return String
    */
  def fullSlug(parentPage: Option[Page]): String = parentPage.fold(slug)(pp => s"${pp.slug}/$slug")
}

object Page extends DefaultModelCompanion[Page, PageTable](TableQuery[PageTable]) {

  def apply(
      projectId: DbRef[Project],
      name: String,
      content: Option[String],
      isDeletable: Boolean,
      parentId: Option[DbRef[Page]]
  ): Page = Page(
    projectId = projectId,
    name = compact(name),
    slug = slugify(name),
    contents = content.map(_.trim),
    isDeletable = isDeletable,
    parentId = parentId
  )

  implicit val query: ModelQuery[Page] =
    ModelQuery.from(this)

  implicit val isProjectOwned: ProjectOwned[Page] = (a: Page) => a.projectId

  /**
    * Returns a template for new Pages.
    *
    * @param title  Page title
    * @param body   Default message
    * @return       Template
    */
  def template(title: String, body: String = ""): String = "# " + title + "\n" + body

  implicit class PageModelOps(private val self: Model[Page]) extends AnyVal {

    /**
      * Returns access to this Page's children (if any).
      *
      * @return Page's children
      */
    def children[V[_, _]: QueryView](view: V[PageTable, Model[Page]]): V[PageTable, Model[Page]] =
      view.filterView(page => page.parentId.isDefined && page.parentId.get === self.id.value)
  }

}
