package db.impl.query.apiv2

import controllers.apiv2.Pages
import ore.db.DbRef
import ore.models.project.{Page, Project}

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.implicits.javasql._
import doobie.implicits.javatime.JavaTimeLocalDateMeta

object PageQueries extends APIV2Queries {

  def getPage(
      projectOwner: String,
      projectSlug: String,
      page: String
  ): Query0[(DbRef[Project], DbRef[Page], String, Option[String])] =
    sql"""|WITH RECURSIVE pages_rec(n, name, slug, contents, id, project_id) AS (
          |    SELECT 2, pp.name, pp.slug, pp.contents, pp.id, pp.project_id
          |        FROM project_pages pp
          |                 JOIN projects p ON pp.project_id = p.id
          |        WHERE p.owner_name = $projectOwner AND lower(p.slug) = lower($projectSlug)
          |          AND lower(split_part($page, '/', 1)) = lower(pp.slug)
          |          AND pp.parent_id IS NULL
          |    UNION
          |    SELECT pr.n + 1, pp.name, pp.slug, pp.contents, pp.id, pp.project_id
          |        FROM pages_rec pr,
          |             project_pages pp
          |        WHERE pp.project_id = pr.project_id
          |          AND pp.parent_id = pr.id
          |          AND lower(split_part($page, '/', pr.n)) = lower(pp.slug)
          |)
          |SELECT pp.project_id, pp.id, pp.name, pp.contents
          |    FROM pages_rec pp
          |    WHERE lower(pp.slug) = lower(split_part($page, '/', array_length(regexp_split_to_array($page, '/'), 1)));""".stripMargin
      .query[(DbRef[Project], DbRef[Page], String, Option[String])]

  def pageList(
      projectOwner: String,
      projectSlug: String
  ): Query0[(DbRef[Project], DbRef[Page], List[String], List[String], Boolean)] =
    sql"""|WITH RECURSIVE pages_rec(name, slug, id, project_id, navigational) AS (
          |    SELECT ARRAY[pp.name]::TEXT[], ARRAY[pp.slug]::TEXT[], pp.id, pp.project_id, pp.contents IS NULL
          |        FROM project_pages pp
          |                 JOIN projects p ON pp.project_id = p.id
          |        WHERE p.owner_name = $projectOwner AND lower(p.slug) = lower($projectSlug)
          |          AND pp.parent_id IS NULL
          |    UNION
          |    SELECT array_append(pr.name, pp.name::TEXT), array_append(pr.slug, pp.slug::TEXT), pp.id, pp.project_id, pp.contents IS NULL
          |        FROM pages_rec pr,
          |             project_pages pp
          |        WHERE pp.project_id = pr.project_id
          |          AND pp.parent_id = pr.id
          |)
          |SELECT pp.project_id, pp.id, pp.name, pp.slug, navigational
          |    FROM pages_rec pp ORDER BY pp.name;""".stripMargin
      .query[(DbRef[Project], DbRef[Page], List[String], List[String], Boolean)]

  def patchPage(
      patch: Pages.PatchPageF[Option],
      newSlug: Option[String],
      id: DbRef[Page],
      parentId: Option[Option[DbRef[Page]]]
  ): doobie.Update0 = {
    val sets = Fragments.setOpt(
      patch.name.map(n => fr"name = $n"),
      newSlug.map(n => fr"slug = $n"),
      patch.content.map(c => fr"contents = $c"),
      parentId.map(p => fr"parent_id = $p")
    )
    (sql"UPDATE project_pages " ++ sets ++ fr"WHERE id = $id").update
  }
}
