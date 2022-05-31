package db

import java.time.LocalDate

import db.impl.query.AppQueries
import ore.OreConfig
import ore.data.project.Category
import ore.models.project.ProjectSortingStrategy

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import pureconfig.ConfigSource
import pureconfig.generic.auto._

@RunWith(classOf[JUnitRunner])
class AppQueriesSpec extends DbSpec {

  implicit val config: OreConfig = ConfigSource.default.loadOrThrow[OreConfig]

  test("GetQueue") {
    check(AppQueries.getQueue)
  }

  test("Flags") {
    check(AppQueries.flags)
  }

  /* Wrong nullness reported
  test("GetUnhealtyProjects") {
    check(AppQueries.getUnhealtyProjects(30.days))
  }
   */

  test("GetErroredJobs") {
    check(AppQueries.erroredJobs)
  }

  test("GetReviewActivity") {
    check(AppQueries.getReviewActivity("Foo"))
  }

  test("GetFlagActivity") {
    check(AppQueries.getFlagActivity("Foo"))
  }

  test("GetStats") {
    check(AppQueries.getStats(LocalDate.now(), LocalDate.now()))
  }

  /* Relies on a view and as such can't test NULL stuff reliably
  test("GetLog") {
    check(AppQueries.getLog(Some(1), Some(0), Some(0), Some(0), Some(0), Some(0), Some(0)))
  }
   */

  test("GetVisibilityNeedsApproval") {
    check(AppQueries.getVisibilityNeedsApproval)
  }

  test("GetVisibilityWaitingProject") {
    check(AppQueries.getVisibilityWaitingProject)
  }

  test("SitemapIndexUsers") {
    check(AppQueries.sitemapIndexUsers)
  }

  /* Legacy stuff. Throws warnings but we don't care too much
  test("ApiV1IdSearch") {
    check(AppQueries.apiV1IdSearch(Some("foo"), List(Category.AdminTools), ProjectSortingStrategy.Default, 5, 5))
  }
 */
}
