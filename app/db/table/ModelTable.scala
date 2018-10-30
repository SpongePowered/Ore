package db.table

import java.sql.Timestamp

import db.impl.OrePostgresDriver.api._
import db.{Model, DbRef}

import slick.lifted.Tag

/**
  * Represents a Table in the database that contains [[Model]]s.
  */
abstract class ModelTable[M <: Model](tag: Tag, name: String) extends Table[M](tag, name) {

  /** The Model's primary key column */
  def id = column[DbRef[M]]("id", O.PrimaryKey, O.AutoInc)

  /** The [[java.sql.Timestamp]] instant of when a Model was created. */
  def createdAt = column[Timestamp]("created_at")

}
