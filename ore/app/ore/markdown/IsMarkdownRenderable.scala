package ore.markdown

import play.twirl.api.Html

import ore.db.impl.common.VisibilityChange
import ore.models.admin.Message
import ore.models.project.{Note, Version}

import simulacrum.typeclass

@typeclass trait IsMarkdownRenderable[A] {

  def render(a: A)(renderer: MarkdownRenderer): Html
}
object IsMarkdownRenderable {

  def fieldRenderer[A](access: A => String): IsMarkdownRenderable[A] = new IsMarkdownRenderable[A] {
    override def render(a: A)(renderer: MarkdownRenderer): Html = renderer.render(access(a))
  }

  implicit val visibilityChangeRenderable: IsMarkdownRenderable[VisibilityChange] = fieldRenderer(_.comment)

  implicit val reviewMessageIsRenderable: IsMarkdownRenderable[Message] = fieldRenderer(_.message)

  implicit val versionIsRenderabble: IsMarkdownRenderable[Version] = fieldRenderer(_.description.getOrElse(""))

  implicit val projectNoteIsRenderable: IsMarkdownRenderable[Note] = fieldRenderer(_.message)
}
