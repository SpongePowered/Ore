package db.apiv2

import controllers.apiv2.Projects
import db.DbSpec
import db.impl.query.apiv2.ProjectQueries
import ore.OreConfig
import ore.data.project.Category

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import pureconfig.ConfigSource

@RunWith(classOf[JUnitRunner])
class ProjectQueriesSpec extends DbSpec {

  implicit val config: OreConfig = ConfigSource.default.loadOrThrow[OreConfig]

  /* Can't test as it requires a few values we don't have
  test("projectQuery") {
    check(
      ProjectQueries.projectQuery(
        Some("foo"),
        List(Category.AdminTools),
        List(("bar", Some("baz")), ("bin", None)),
        List(Version.Stability.Stable),
        Some("qoux"),
        Some("Spongie"),
        canSeeHidden = true,
        Some(5L),
        ProjectSortingStrategy.Default,
        orderWithRelevance = true,
        exactSearch = false,
        5,
        5
      )
    )
  }
   */

  test("updateProject") {
    check(
      ProjectQueries.updateProject(
        5L,
        Projects.EditableProjectF[Option](
          Some("baz"),
          Projects.EditableProjectNamespaceF[Option](Some("bin")),
          Some(Category.AdminTools),
          Some(Some("foobar")),
          Projects.EditableProjectSettingsF[Option](
            Some(List("foo", "bar", "baz")),
            Some(Some("home")),
            Some(Some("issues")),
            Some(Some("source")),
            Some(Some("support")),
            Projects.EditableProjectLicenseF[Option](
              Some(Some("MIT")),
              Some(Some("MIT link"))
            ),
            Some(true)
          )
        )
      )
    )
  }
}
