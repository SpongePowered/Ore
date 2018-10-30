package db.impl.schema

import db.DbRef
import db.impl.OrePostgresDriver.api._
import db.table.ModelTable
import models.project.UnsafeDownload
import models.user.User
import ore.project.io.DownloadType

import com.github.tminglei.slickpg.InetString

class UnsafeDownloadsTable(tag: RowTag) extends ModelTable[UnsafeDownload](tag, "project_version_unsafe_downloads") {

  def userId       = column[DbRef[User]]("user_id")
  def address      = column[InetString]("address")
  def downloadType = column[DownloadType]("download_type")

  override def * = mkProj((id.?, createdAt.?, userId.?, address, downloadType))(mkTuple[UnsafeDownload]())
}
