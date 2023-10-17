package ore.db.impl

import java.sql.{PreparedStatement, ResultSet}
import java.time.OffsetDateTime
import java.util.Locale

import scala.reflect.ClassTag

import ore.data.project.{Category, FlagReason}
import ore.data.user.notification.NotificationType
import ore.data.{Color, DownloadType, Prompt}
import ore.db.OreProfile
import ore.models.Job
import ore.models.project.{ReviewState, TagColor, Version, Visibility}
import ore.models.user.{LoggedActionContext, LoggedActionType}
import ore.permission.Permission
import ore.permission.role.{Role, RoleCategory}

import cats.data.{NonEmptyList => NEL}
import com.github.tminglei.slickpg._
import com.github.tminglei.slickpg.agg.PgAggFuncSupport
import com.github.tminglei.slickpg.window.PgWindowFuncSupport
import enumeratum.values.{SlickValueEnumSupport, StringEnum, StringEnumEntry}
import org.postgresql.util.PGobject
import slick.jdbc.JdbcType

/**
  * Custom Postgres driver to support array data and custom type mappings.
  */
trait OrePostgresDriver
    extends OreProfile
    with ExPostgresProfile
    with PgArraySupport
    with PgAggFuncSupport
    with PgWindowFuncSupport
    with PgNetSupport
    with PgCirceJsonSupport
    with PgEnumSupport
    with PgHStoreSupport
    with SlickValueEnumSupport { self =>

  override val columnTypes = new JdbcTypes

  override val api: OreDriver.type = OreDriver

  def pgjson = "jsonb"

  class JdbcTypes extends super.JdbcTypes {

    override val offsetDateTimeType: OffsetDateTimeJdbcType = new OffsetDateTimeJdbcType {
      override def sqlType: Int = java.sql.JDBCType.TIMESTAMP_WITH_TIMEZONE.getVendorTypeNumber

      override def setValue(v: OffsetDateTime, p: PreparedStatement, idx: Int): Unit = p.setObject(idx, v, sqlType)

      override def getValue(r: ResultSet, idx: Int): OffsetDateTime = r.getObject(idx, classOf[OffsetDateTime])

      override def updateValue(v: OffsetDateTime, r: ResultSet, idx: Int): Unit = r.updateObject(idx, v, sqlType)
    }
  }

  object OreDriver extends API with ArrayImplicits with NetImplicits with JsonImplicits with HStoreImplicits {
    type ModelTable[M]          = self.ModelTable[M]
    type AssociativeTable[A, B] = self.AssociativeTable[A, B]
    type ModelFilter[T]         = self.ModelFilter[T]
    val ModelFilter: self.ModelFilter.type = self.ModelFilter

    def pgEnumForValueEnum[E <: StringEnumEntry: ClassTag](typeName: String, enum: StringEnum[E]): JdbcType[E] =
      createEnumJdbcType[E](typeName, _.value, enum.withValue, quoteName = false)

    implicit val colorTypeMapper: BaseColumnType[Color]           = mappedColumnTypeForValueEnum(Color)
    implicit val tagColorTypeMapper: BaseColumnType[TagColor]     = mappedColumnTypeForValueEnum(TagColor)
    implicit val roleTypeTypeMapper: BaseColumnType[Role]         = mappedColumnTypeForValueEnum(Role)
    implicit val categoryTypeMapper: BaseColumnType[Category]     = mappedColumnTypeForValueEnum(Category)
    implicit val flagReasonTypeMapper: BaseColumnType[FlagReason] = mappedColumnTypeForValueEnum(FlagReason)
    implicit val notificationTypeTypeMapper: BaseColumnType[NotificationType] =
      mappedColumnTypeForValueEnum(NotificationType)
    implicit val promptTypeMapper: BaseColumnType[Prompt]             = mappedColumnTypeForValueEnum(Prompt)
    implicit val downloadTypeTypeMapper: BaseColumnType[DownloadType] = mappedColumnTypeForValueEnum(DownloadType)
    implicit val visibilityTypeMapper: BaseColumnType[Visibility]     = mappedColumnTypeForValueEnum(Visibility)
    implicit def loggedActionTypeMapper[Ctx]: BaseColumnType[LoggedActionType[Ctx]] =
      pgEnumForValueEnum("LOGGED_ACTION_TYPE", LoggedActionType)
        .asInstanceOf[BaseColumnType[LoggedActionType[Ctx]]] // scalafix:ok
    implicit def loggedActionContextMapper[Ctx]: BaseColumnType[LoggedActionContext[Ctx]] =
      mappedColumnTypeForValueEnum(LoggedActionContext)
        .asInstanceOf[BaseColumnType[LoggedActionContext[Ctx]]] // scalafix:ok
    implicit val reviewStateTypeMapper: BaseColumnType[ReviewState] = mappedColumnTypeForValueEnum(ReviewState)

    implicit val stabilityTypeMapper: BaseColumnType[Version.Stability] =
      pgEnumForValueEnum("STABILITY", Version.Stability)
    implicit val releaseTypeTypeMapper: BaseColumnType[Version.ReleaseType] =
      pgEnumForValueEnum("RELEASE_TYPE", Version.ReleaseType)

    implicit val langTypeMapper: BaseColumnType[Locale] =
      MappedJdbcType.base[Locale, String](_.toLanguageTag, Locale.forLanguageTag)

    implicit val permissionTypeMapper: BaseColumnType[Permission] = new DriverJdbcType[Permission] {
      override def sqlType: Int = java.sql.Types.BIT

      override def setValue(v: Permission, p: PreparedStatement, idx: Int): Unit = {
        val obj = new PGobject
        obj.setType("bit")
        obj.setValue(v.toBinString)
        p.setObject(idx, obj)
      }

      override def getValue(r: ResultSet, idx: Int): Permission = {
        val str = r.getString(idx)
        if (str == null) null.asInstanceOf[Permission]
        else Permission.fromBinString(str).get
      }

      override def updateValue(v: Permission, r: ResultSet, idx: Int): Unit = {
        val obj = new PGobject
        obj.setType("bit")
        obj.setValue(v.toBinString)
        r.updateObject(idx, obj)
      }

      override def hasLiteralForm: Boolean = false
    }

    /*
    implicit def dbRefBaseType[A]: BaseColumnType[DbRef[A]] = longColumnType.asInstanceOf[BaseColumnType[DbRef[A]]]
    implicit def dbRefArrayBaseType[A]: BaseColumnType[List[DbRef[A]]] =
      simpleLongListTypeMapper.asInstanceOf[BaseColumnType[List[DbRef[A]]]]
     */

    implicit val roleTypeListTypeMapper: DriverJdbcType[List[Role]] = new AdvancedArrayJdbcType[Role](
      "varchar",
      str => utils.SimpleArrayUtils.fromString[Role](s => Role.withValue(s))(str).orNull,
      value => utils.SimpleArrayUtils.mkString[Role](_.value)(value)
    ).to(_.toList)

    implicit val promptListTypeMapper: DriverJdbcType[List[Prompt]] = new AdvancedArrayJdbcType[Prompt](
      "int2",
      str => utils.SimpleArrayUtils.fromString[Prompt](s => Prompt.withValue(Integer.parseInt(s)))(str).orNull,
      value => utils.SimpleArrayUtils.mkString[Prompt](_.value.toString)(value)
    ).to(_.toList)

    implicit val roleCategoryTypeMapper: JdbcType[RoleCategory] = pgEnumForValueEnum("ROLE_CATEGORY", RoleCategory)
    implicit val jobStateTypeMapper: JdbcType[Job.JobState]     = pgEnumForValueEnum("JOB_STATE", Job.JobState)

    implicit val jobTypeTypeMapper: JdbcType[Job.JobType] = mappedColumnTypeForValueEnum(Job.JobType)

    implicit def nelArrayMapper[A](
        implicit base: BaseColumnType[List[A]]
    ): BaseColumnType[NEL[A]] = MappedJdbcType.base[NEL[A], List[A]](_.toList, NEL.fromListUnsafe)

    val WindowFunctions: WindowFunctions = new WindowFunctions {}
  }

}

object OrePostgresDriver extends OrePostgresDriver
