package util

import play.api.mvc.QueryStringBindable

import controllers.apiv2.Users.UserSortingStrategy
import ore.data.project.Category
import ore.models.project.{ProjectSortingStrategy, Version}
import ore.permission.NamedPermission
import ore.permission.role.Role

object APIBinders {

  private def objBindable[A](name: String, decode: String => Option[A], encode: A => String) =
    new QueryStringBindable[A] {
      override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, A]] =
        params.get(key).flatMap(_.headOption).map(str => decode(str).toRight(s"$str is not a valid $name"))

      override def unbind(key: String, value: A): String = s"$key=${encode(value)}"
    }

  implicit val categoryQueryStringBindable: QueryStringBindable[Category] =
    objBindable("category", s => Category.values.find(_.apiName == s), _.apiName)

  implicit val namedPermissionQueryStringBindable: QueryStringBindable[NamedPermission] =
    objBindable("permission", NamedPermission.withNameOption, _.entryName)

  implicit val projectSortingStrategyQueryStringBindable: QueryStringBindable[ProjectSortingStrategy] =
    objBindable("sorting strategy", s => ProjectSortingStrategy.values.find(_.apiName == s), _.apiName)

  implicit val userSortingStrategyQueryStringBindable: QueryStringBindable[UserSortingStrategy] =
    objBindable("sorting strategy", UserSortingStrategy.withValueOpt, _.value)

  implicit val stabilityStringBindable: QueryStringBindable[Version.Stability] =
    objBindable("stability", Version.Stability.withValueOpt, _.value)

  implicit val releaseTypeStringBindable: QueryStringBindable[Version.ReleaseType] =
    objBindable("release type", Version.ReleaseType.withValueOpt, _.value)

  implicit val roleStringBindable: QueryStringBindable[Role] =
    objBindable("role", Role.withValueOpt, _.value)

}
