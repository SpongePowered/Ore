import java.security.Security
import javax.inject.{Inject, Singleton}

import scala.concurrent.ExecutionContext

import db.ModelService
import db.impl.DbUpdateTask
import discourse.OreDiscourseApi
import ore.OreConfig
import ore.project.ProjectTask

import com.typesafe.scalalogging
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
  * Handles initialization logic for the application.
  */
abstract class Bootstrap(
    service: ModelService,
    forums: OreDiscourseApi,
    config: OreConfig,
    projectTask: ProjectTask,
    dbUpdateTask: DbUpdateTask,
    ec: ExecutionContext
) {

  private val Logger = scalalogging.Logger("Bootstrap")

  Logger.info("Initializing Ore...")
  val time: Long = System.currentTimeMillis()

  this.forums.start(
    ec,
    service,
    config
  )

  this.projectTask.start()
  this.dbUpdateTask.start()

  if (this.config.security.requirePgp)
    Security.addProvider(new BouncyCastleProvider)

  Logger.info(s"Ore Initialized (${System.currentTimeMillis() - time}ms).")

}

@Singleton
class BootstrapImpl @Inject()(
    modelService: ModelService,
    forums: OreDiscourseApi,
    config: OreConfig,
    projectTask: ProjectTask,
    dbUpdateTask: DbUpdateTask,
    ec: ExecutionContext
) extends Bootstrap(modelService, forums, config, projectTask, dbUpdateTask, ec)
