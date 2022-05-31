package db.apiv2

import controllers.apiv2.Versions
import db.DbSpec
import db.impl.query.apiv2.VersionQueries
import ore.OreConfig
import ore.models.project.Version

import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import pureconfig.ConfigSource

@RunWith(classOf[JUnitRunner])
class VersionQueriesSpec extends DbSpec {

  implicit val config: OreConfig = ConfigSource.default.loadOrThrow[OreConfig]

  test("versionQuery") {
    check(
      VersionQueries.versionQuery(
        "foo",
        "bar",
        Some("baz"),
        List(("bar", Some("baz")), ("bin", None)),
        List(Version.Stability.Stable),
        List(Version.ReleaseType.MajorUpdate),
        canSeeHidden = true,
        Some(5L),
        5,
        5
      )
    )
  }

  test("updateProject") {
    check(
      VersionQueries.updateVersion(
        "foo",
        "bar",
        "baz",
        Versions.DbEditableVersionF[Option](
          Some(Version.Stability.Stable),
          Some(Some(Version.ReleaseType.MajorUpdate))
        )
      )
    )
  }
}
