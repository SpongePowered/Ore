package db.apiv2

import controllers.apiv2.Pages
import db.DbSpec
import db.impl.query.apiv2.PageQueries
import ore.OreConfig

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import pureconfig.ConfigSource

@RunWith(classOf[JUnitRunner])
class PageQueriesSpec extends DbSpec {

  implicit val config: OreConfig = ConfigSource.default.loadOrThrow[OreConfig]

  test("getPage") {
    check(PageQueries.getPage(5L, "baz"))
  }

  test("pageList") {
    check(PageQueries.pageList(5L))
  }

  test("patchPage") {
    check(
      PageQueries
        .patchPage(Pages.PatchPageF[Option](Some("foo"), Some(Some("bar")), None), Some("baz"), 5L, Some(Some(5L)))
    )
  }
}
