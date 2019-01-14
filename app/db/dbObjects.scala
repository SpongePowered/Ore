package db

import scala.language.implicitConversions

import java.sql.Timestamp

sealed trait DbInitialized[A] {
  def value: A
  private[db] def unsafeToOption: Option[A]
  override def toString: String = unsafeToOption match {
    case Some(value) => value.toString
    case None        => "DbInitialized.Uninitialized"
  }
}

sealed trait ObjId[A] extends DbInitialized[DbRef[A]]
object ObjId {
  implicit def unwrapObjId[A](objId: ObjId[A]): DbRef[A] = objId.value

  private[db] class UnsafeUninitialized[A] extends ObjId[A] {
    override def value: Nothing                              = sys.error("Tried to access uninitialized ObjId. This should be impossible")
    override private[db] def unsafeToOption: Option[Nothing] = None
  }

  private class RealObjId[A](val value: DbRef[A]) extends ObjId[A] {
    override private[db] def unsafeToOption: Option[DbRef[A]] = Some(value)
  }

  def apply[A](id: DbRef[A]): ObjId[A] = new RealObjId(id)

  private[db] def unsafeFromOption[A](option: Option[DbRef[A]]): ObjId[A] = option match {
    case Some(id) => ObjId(id)
    case None     => new UnsafeUninitialized
  }
}

sealed trait ObjTimestamp extends DbInitialized[Timestamp]
object ObjTimestamp {
  implicit def unwrapObjTimestamp(objTimestamp: ObjTimestamp): Timestamp = objTimestamp.value

  private[db] object UnsafeUninitialized extends ObjTimestamp {
    override def value: Nothing                  = sys.error("Tried to access uninitialized ObjTimestamp. This should be impossible")
    override def unsafeToOption: Option[Nothing] = None
  }

  private class RealObjTimestamp(val value: Timestamp) extends ObjTimestamp {
    override private[db] def unsafeToOption: Option[Timestamp] = Some(value)
  }

  def apply(timestamp: Timestamp): ObjTimestamp = new RealObjTimestamp(timestamp)

  private[db] def unsafeFromOption(option: Option[Timestamp]): ObjTimestamp = option match {
    case Some(time) => ObjTimestamp(time)
    case None       => UnsafeUninitialized
  }
}
