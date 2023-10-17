package ore.rest

import play.api.libs.json.Json.obj
import play.api.libs.json._

import ore.db.Model
import ore.models.api.ProjectApiKey
import ore.models.project._

/**
  * Contains implicit JSON [[Writes]] for the Ore API.
  */
trait OreWrites {

  implicit val projectApiKeyWrites: Writes[Model[ProjectApiKey]] = (key: Model[ProjectApiKey]) =>
    obj(
      "id"        -> key.id.value,
      "createdAt" -> key.createdAt.value,
      "keyType"   -> obj("id" -> 0, "name" -> "deployment"),
      "projectId" -> key.projectId,
      "value"     -> key.value
    )

  implicit val pageWrites: Writes[Model[Page]] = (page: Model[Page]) =>
    obj(
      "id"        -> page.id.value,
      "createdAt" -> page.createdAt.toString,
      "parentId"  -> page.parentId,
      "name"      -> page.name,
      "slug"      -> page.slug
    )

  implicit val channelWrites: Writes[FakeChannel] = (channel: FakeChannel) =>
    obj("name" -> channel.name, "color" -> channel.color.background, "nonReviewed" -> channel.isNonReviewed)

  implicit val tagColorWrites: Writes[TagColor] = (tagColor: TagColor) => {
    obj(
      "id"              -> tagColor.value,
      "backgroundColor" -> tagColor.background,
      "foregroundColor" -> tagColor.foreground
    )
  }
}
object OreWrites extends OreWrites
