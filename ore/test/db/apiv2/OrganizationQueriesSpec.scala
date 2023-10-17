package db.apiv2

import db.DbSpec
import db.impl.query.apiv2.OrganizationQueries
import ore.OreConfig

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import pureconfig.ConfigSource

@RunWith(classOf[JUnitRunner])
class OrganizationQueriesSpec extends DbSpec {

  implicit val config: OreConfig = ConfigSource.default.loadOrThrow[OreConfig]

  test("organizationQuery") {
    check(OrganizationQueries.organizationQuery("foo"))
  }

  test("canUploadToOrg") {
    check(OrganizationQueries.canUploadToOrg(5L, "foo"))
  }
}
