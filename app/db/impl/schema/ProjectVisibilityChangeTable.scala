package db.impl.schema

import db.DbRef
import db.impl.OrePostgresDriver.api._
import db.impl.table.common.VisibilityChangeColumns
import db.table.ModelTable
import models.admin.ProjectVisibilityChange
import models.project.Project

class ProjectVisibilityChangeTable(tag: Tag)
    extends ModelTable[ProjectVisibilityChange](tag, "project_visibility_changes")
    with VisibilityChangeColumns[ProjectVisibilityChange] {

  def projectId = column[DbRef[Project]]("project_id")

  override def * =
    mkProj((id.?, createdAt.?, createdBy.?, projectId, comment, resolvedAt.?, resolvedBy.?, visibility))(
      mkTuple[ProjectVisibilityChange]()
    )
}
