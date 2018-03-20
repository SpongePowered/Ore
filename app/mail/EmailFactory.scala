package mail

import controllers.sugar.Requests.OreRequest
import db.ModelService
import db.impl.access.UserBase
import javax.inject.Inject
import models.user.User
import ore.OreConfig
import play.api.i18n.{I18nSupport, Lang, MessagesApi}

final class EmailFactory @Inject()(override val messagesApi: MessagesApi,
                                   implicit val config: OreConfig,
                                   implicit val service: ModelService) extends I18nSupport {

  val PgpUpdated = "email.pgpUpdate"
  val AccountUnlocked = "email.accountUnlock"

  implicit val users = this.service.getModelBase(classOf[UserBase])
  implicit val lang = Lang.defaultLang

  def create(user: User, id: String)(implicit request: OreRequest[_]): Email = {

      Email(
      recipient = user.email.get,
      subject = this.messagesApi(s"$id.subject"),
      content = views.html.utils.email(
        title = s"$id.subject",
        recipient = user.name,
        body = s"$id.body"
      )
    )
  }

}
