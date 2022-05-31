package controllers.apiv2

import play.api.http.HttpErrorHandler
import play.api.inject.ApplicationLifecycle
import play.api.mvc.{Action, AnyContent}

import controllers.OreControllerComponents
import controllers.apiv2.helpers.{APIScope, ApiError, ApiErrors}
import db.impl.query.apiv2.PageQueries
import models.protocols.APIV2
import ore.db.DbRef
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.PageTable
import ore.models.project.{Page, Project}
import ore.permission.Permission
import ore.util.StringUtils
import util.PatchDecoder
import util.syntax._

import cats.data.Validated
import cats.instances.option._
import cats.syntax.all._
import io.circe.{Decoder, Json}
import slick.lifted.TableQuery
import perspective._
import perspective.syntax.all._
import perspective.macros.Derive
import zio.ZIO
import zio.interop.catz._

class Pages(val errorHandler: HttpErrorHandler, lifecycle: ApplicationLifecycle)(
    implicit oreComponents: OreControllerComponents
) extends AbstractApiV2Controller(lifecycle) {

  def showPages(projectOwner: String, projectSlug: String): Action[AnyContent] =
    CachingApiAction(Permission.ViewPublicInfo, APIScope.GlobalScope).asyncF {
      service.runDbCon(PageQueries.pageList(projectOwner, projectSlug).to[Vector]).flatMap { pages =>
        if (pages.isEmpty) ZIO.fail(NotFound)
        else ZIO.succeed(Ok(APIV2.PageList(pages.map(t => APIV2.PageListEntry(t._3, t._4, t._5)))))
      }
    }

  def showPageAction(projectOwner: String, projectSlug: String, page: String): Action[AnyContent] =
    CachingApiAction(Permission.ViewPublicInfo, APIScope.GlobalScope).asyncF {
      service.runDbCon(PageQueries.getPage(projectOwner, projectSlug, page).option).get.orElseFail(NotFound).map {
        case (_, _, name, contents) =>
          Ok(APIV2.Page(name, contents))
      }
    }

  def putPage(projectOwner: String, projectSlug: String, page: String): Action[APIV2.Page] =
    ApiAction(Permission.EditPage, APIScope.ProjectScope(projectOwner, projectSlug))
      .asyncF(parseCirce.decodeJson[APIV2.Page]) { c =>
        val newName = StringUtils.compact(c.body.name)
        val content = c.body.content

        val pageArr  = page.split("/")
        val pageInit = pageArr.init.mkString("/")
        val slug     = StringUtils.slugify(pageArr.last) //TODO: Check ASCII

        val updateExisting =
          service.runDbCon(PageQueries.getPage(projectOwner, projectSlug, page).option).get.flatMap {
            case (_, id, _, _) =>
              service
                .runDBIO(
                  TableQuery[PageTable].filter(_.id === id).map(p => (p.name, p.contents)).update((newName, content))
                )
                .as(Ok(APIV2.Page(newName, content)))
          }

        def insertNewPage(projectId: DbRef[Project], parentId: Option[DbRef[Page]]) =
          service
            .insert(Page(projectId, parentId, newName, slug, isDeletable = true, content))
            .as(Created(APIV2.Page(newName, content)))

        val createNew =
          if (page.contains("/")) {
            service
              .runDbCon(PageQueries.getPage(projectOwner, projectSlug, pageInit).option)
              .get
              .orElseFail(NotFound)
              .flatMap {
                case (projectId, parentId, _, _) =>
                  insertNewPage(projectId, Some(parentId))
              }
          } else {
            projects
              .withSlug(projectOwner, projectSlug)
              .get
              .orElseFail(NotFound)
              .map(_.id)
              .flatMap(insertNewPage(_, None))
          }

        if (page == Page.homeName && content.fold(0)(_.length) < Page.minLength)
          ZIO.fail(BadRequest(ApiError("Too short content")))
        else if (content.fold(0)(_.length) > Page.maxLengthPage) ZIO.fail(BadRequest(ApiError("Too long content")))
        else updateExisting.orElse(createNew)
      }

  def patchPage(projectOwner: String, projectSlug: String, page: String): Action[Json] =
    ApiAction(Permission.EditPage, APIScope.ProjectScope(projectOwner, projectSlug))
      .asyncF(parseCirce.json) { implicit request =>
        val root = request.body.hcursor

        val res: Decoder.AccumulatingResult[Pages.PatchPageF[Option]] = Pages.PatchPageF.patchDecoder.traverseKC(
          λ[PatchDecoder ~>: Compose2[Decoder.AccumulatingResult, Option, *]](_.decode(root))
        )

        res match {
          case Validated.Valid(a) =>
            val newName = a.copy[Option](
              name = a.name.map(StringUtils.compact)
            )

            val slug = newName.name.map(StringUtils.slugify)

            val oldPage =
              service.runDbCon(PageQueries.getPage(projectOwner, projectSlug, page).option).get.orElseFail(NotFound)
            val newParent = newName.parent
              .map(
                _.map(p => service.runDbCon(PageQueries.getPage(projectOwner, projectSlug, p).option).get.map(_._2)).sequence
              )
              .sequence
              .orElseFail(BadRequest(ApiError("Unknown parent")))

            val runRename = (oldPage <&> newParent).flatMap {
              case ((_, id, name, contents), parentId) =>
                service
                  .runDbCon(PageQueries.patchPage(newName, slug, id, parentId).run)
                  .as(Ok(APIV2.Page(newName.name.getOrElse(name), newName.content.getOrElse(contents))))
            }

            if (newName.content.flatten.fold(0)(_.length) > Page.maxLengthPage)
              ZIO.fail(BadRequest(ApiError("Too long content")))
            else runRename
          case Validated.Invalid(e) => ZIO.fail(BadRequest(ApiErrors(e.map(_.show))))
        }
      }

  def deletePage(projectOwner: String, projectSlug: String, page: String): Action[AnyContent] =
    ApiAction(Permission.EditPage, APIScope.ProjectScope(projectOwner, projectSlug)).asyncF {
      service
        .runDbCon(PageQueries.getPage(projectOwner, projectSlug, page).option)
        .get
        .orElseFail(NotFound)
        .flatMap {
          case (_, id, _, _) =>
            //TODO: In the future when we represent the tree in a better way, just promote all children one level up
            service.deleteWhere(Page)(p =>
              p.id === id && p.isDeletable && TableQuery[PageTable].filter(_.parentId === id).size === 0
            )
        }
        .map {
          case 0 => BadRequest(ApiError("Page not deletable"))
          case _ => NoContent
        }
    }
}
object Pages {
  case class PatchPageF[F[_]](
      name: F[String],
      content: F[Option[String]],
      parent: F[Option[String]]
  )
  object PatchPageF {
    implicit val F: TraverseKC[PatchPageF] with RepresentableKC[PatchPageF] { type RepresentationK[A] = Finite[3] } =
      Derive.allKC[PatchPageF, λ[A => Finite[3]]]

    val patchDecoder: PatchPageF[PatchDecoder] = {
      val namesWithImplicits: PatchPageF[Tuple2K[Derive.Names, Decoder, *]] =
        Derive.namesWithImplicitsC[PatchPageF, Decoder]
      PatchDecoder.fromName(namesWithImplicits)(
        io.circe.derivation.renaming.snakeCase
      )
    }
  }
}
