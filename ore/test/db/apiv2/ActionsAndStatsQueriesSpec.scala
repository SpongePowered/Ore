package db.apiv2

import java.time.LocalDate

import db.DbSpec
import db.impl.query.apiv2.ActionsAndStatsQueries
import ore.OreConfig
import ore.models.project.ProjectSortingStrategy

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import pureconfig.ConfigSource

@RunWith(classOf[JUnitRunner])
class ActionsAndStatsQueriesSpec extends DbSpec {

  implicit val config: OreConfig = ConfigSource.default.loadOrThrow[OreConfig]

  test("starredQuery") {
    check(
      ActionsAndStatsQueries.starredQuery("foo", canSeeHidden = true, Some(5L), ProjectSortingStrategy.Default, 10, 10)
    )
  }

  test("watchingQuery") {
    check(
      ActionsAndStatsQueries.watchingQuery("foo", canSeeHidden = true, Some(5L), ProjectSortingStrategy.Default, 10, 10)
    )
  }

  test("projectStats") {
    check(ActionsAndStatsQueries.projectStats(5L, LocalDate.now(), LocalDate.now().minusDays(3)))
  }

  test("versionStats") {
    check(ActionsAndStatsQueries.versionStats(5L, "baz", LocalDate.now(), LocalDate.now().minusDays(3)))
  }

}
