package db.apiv2

import db.DbSpec
import db.impl.query.apiv2.AuthQueries
import ore.OreConfig
import ore.permission.Permission

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import pureconfig.ConfigSource

@RunWith(classOf[JUnitRunner])
class AuthQueriesSpec extends DbSpec {

  implicit val config: OreConfig = ConfigSource.default.loadOrThrow[OreConfig]

  test("getApiAuthInfo") {
    check(AuthQueries.getApiAuthInfo("foo"))
  }

  test("findApiKey") {
    check(AuthQueries.findApiKey("foo", "bar"))
  }

  test("createApiKey") {
    check(AuthQueries.createApiKey("foo", 5L, "bar", "baz", Permission.All))
  }

  test("deleteApiKey") {
    check(AuthQueries.deleteApiKey("foo", 5L))
  }

}
