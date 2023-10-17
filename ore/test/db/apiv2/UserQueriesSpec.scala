package db.apiv2

import controllers.apiv2.Users
import db.DbSpec
import db.impl.query.apiv2.UserQueries
import ore.OreConfig
import ore.permission.role.Role

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import pureconfig.ConfigSource

@RunWith(classOf[JUnitRunner])
class UserQueriesSpec extends DbSpec {

  implicit val config: OreConfig = ConfigSource.default.loadOrThrow[OreConfig]

  test("userSearchQuery") {
    check(
      UserQueries.userSearchQuery(
        Some("foo"),
        5,
        List(Role.SpongeStaff),
        excludeOrganizations = true,
        Users.UserSortingStrategy.Projects,
        sortDescending = true,
        5,
        5
      )
    )
  }

  test("userQuery") {
    check(UserQueries.userQuery("foo"))
  }

  test("getMemberships") {
    check(UserQueries.getMemberships("foo"))
  }

  test("projectMembers") {
    check(UserQueries.projectMembers(5L, 5, 5))
  }

  test("orgaMembers") {
    check(UserQueries.orgaMembers(5L, 5, 5))
  }
}
