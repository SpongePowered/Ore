package ore.rest

import ore.models.project.{TagColor, Version}

case class FakeChannel(
    name: String,
    color: TagColor,
    isNonReviewed: Boolean
)
object FakeChannel {

  def fromVersion(version: Version): FakeChannel = {
    val stability = version.tags.stability
    FakeChannel(
      stability.value.capitalize,
      TagColor.Green,
      stability != Version.Stability.Stable
    )
  }
}
