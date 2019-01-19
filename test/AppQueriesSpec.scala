import play.api.Configuration

import db.query.{AppQueries, UserQueries}
import ore.OreConfig
import ore.project.ProjectSortingStrategy

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class AppQueriesSpec extends DbSpec {

  implicit val config: OreConfig = new OreConfig(
    Configuration.load(getClass.getClassLoader, System.getProperties, Map.empty, allowMissingApplicationConf = false)
  )

  test("GetHomeProjects") {
    check(
      AppQueries
        .getHomeProjects(
          Some(5),
          canSeeHidden = false,
          Nil,
          Nil,
          None,
          ProjectSortingStrategy.Default,
          0,
          50,
          orderWithRelevance = true
        )
    )
  }

  test("GetQueue") {
    check(AppQueries.getQueue)
  }

  test("Flags") {
    check(AppQueries.flags(0))
  }

  test("GetUnhealtyProjects") {
    check(AppQueries.getUnhealtyProjects)
  }

  test("GetReviewActivity") {
    check(AppQueries.getReviewActivity("Foo"))
  }

  test("GetFlagActivity") {
    check(AppQueries.getFlagActivity("Foo"))
  }

  test("GetStats") {
    check(AppQueries.getStats(0, 0))
  }

  test("GetLog") {
    check(AppQueries.getLog(Some(1), Some(0), Some(0), Some(0), Some(0), Some(0), Some(0)))
  }

  test("GetVisibilityNeedsApproval") {
    check(AppQueries.getVisibilityNeedsApproval)
  }

  test("GetVisibilityWaitingProject") {
    check(AppQueries.getVisibilityWaitingProject)
  }
}
