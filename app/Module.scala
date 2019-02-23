import db.ModelService
import db.impl.service.OreModelService
import discourse.{OreDiscourseApi, SpongeForums}
import mail.{Mailer, SpongeMailer}
import ore._
import ore.project.factory.{OreProjectFactory, ProjectFactory}
import ore.rest.{OreRestfulApi, OreRestfulServer}
import security.spauth.{SingleSignOnConsumer, SpongeAuth, SpongeAuthApi, SpongeSingleSignOnConsumer}

import com.google.inject.AbstractModule

/** The Ore Module */
class Module extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[OreRestfulApi]).to(classOf[OreRestfulServer])
    bind(classOf[StatTracker]).to(classOf[OreStatTracker])
    bind(classOf[ProjectFactory]).to(classOf[OreProjectFactory])
    bind(classOf[OreDiscourseApi]).to(classOf[SpongeForums])
    bind(classOf[SpongeAuthApi]).to(classOf[SpongeAuth])
    bind(classOf[ModelService]).to(classOf[OreModelService])
    bind(classOf[Mailer]).to(classOf[SpongeMailer])
    bind(classOf[SingleSignOnConsumer]).to(classOf[SpongeSingleSignOnConsumer])
    bind(classOf[Bootstrap]).to(classOf[BootstrapImpl]).asEagerSingleton()
  }

}
