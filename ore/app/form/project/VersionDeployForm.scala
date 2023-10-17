package form.project

case class VersionDeployForm(
    apiKey: String,
    channel: Option[String],
    recommended: Boolean,
    createForumPost: Boolean,
    changelog: Option[String]
)
