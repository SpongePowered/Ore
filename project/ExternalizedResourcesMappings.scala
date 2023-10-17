import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import com.typesafe.sbt.packager.universal.UniversalPlugin
import sbt.Keys._
import sbt.Path._
import sbt._

/**
  * Externalizes the resources like with Play, but as a standalone plugin.
  */
object ExternalizedResourcesMappings extends AutoPlugin {
  override def requires: Plugins = UniversalPlugin && JavaAppPackaging
  import JavaAppPackaging.autoImport._
  import UniversalPlugin.autoImport._

  object autoImport {
    val externalizedResources = TaskKey[Seq[(File, String)]]("externalizedResources", "The resources to externalize")
    val jarSansExternalized =
      TaskKey[File]("jarSansExternalized", "Creates a jar file that has all the externalized resources excluded")
    val externalizeResourcesExcludes = SettingKey[Seq[File]](
      "externalizeResourcesExcludes",
      "Resources that should not be externalized but stay in the generated jar"
    )
  }

  import autoImport._

  override val projectSettings: Seq[Def.Setting[_]] = Seq(
    externalizeResourcesExcludes := Nil,
    Universal / mappings ++= {
      val resourceMappings = (Compile / externalizedResources).value
      resourceMappings.map {
        case (resource, path) => resource -> ("conf/" + path)
      }
    },
    scriptClasspath := {
      val scriptClasspathValue = scriptClasspath.value
      "../conf/" +: scriptClasspathValue
    },
    scriptClasspathOrdering := Def.taskDyn {
      val oldValue = scriptClasspathOrdering.value
      Def.task {
        // Filter out the regular jar
        val jar                    = (Runtime / packageBin).value
        val jarSansExternalizedObj = (Runtime / jarSansExternalized).value
        oldValue.map {
          case (packageBinJar, _) if jar == packageBinJar =>
            val id  = projectID.value
            val art = (Compile / jarSansExternalized / artifact).value
            val jarName =
              JavaAppPackaging.makeJarName(id.organization, id.name, id.revision, art.name, art.classifier)
            jarSansExternalizedObj -> ("lib/" + jarName)
          case other => other
        }
      }
    }.value
  ) ++ inConfig(Compile)(externalizedSettings)

  def getExternalizedResources(
      rdirs: Seq[File],
      unmanagedResourcesValue: Seq[File],
      externalizeResourcesExcludes: Seq[File]
  ): Seq[(File, String)] =
    (unmanagedResourcesValue --- rdirs --- externalizeResourcesExcludes).pair(relativeTo(rdirs) | flat)

  private def externalizedSettings: Seq[Setting[_]] =
    Defaults.packageTaskSettings(jarSansExternalized, jarSansExternalized / mappings) ++ Seq(
      externalizedResources := getExternalizedResources(
        unmanagedResourceDirectories.value,
        unmanagedResources.value,
        externalizeResourcesExcludes.value
      ),
      jarSansExternalized / mappings := {
        // packageBin mappings have all the copied resources from the classes directory
        // so we need to get the copied resources, and map the source files to the destination files,
        // so we can then exclude the destination files
        val packageBinMappings = (packageBin / mappings).value
        val externalized       = externalizedResources.value.map(_._1).toSet
        val copied             = copyResources.value
        val toExclude = copied.collect {
          case (source, dest) if externalized(source) => dest
        }.toSet
        packageBinMappings.filterNot {
          case (file, _) => toExclude(file)
        }
      },
      jarSansExternalized / artifactClassifier := Option("sans-externalized")
    )
}
