package plugins

import ore.OreConfig
import ore.models.project.PluginInfoParser

import better.files._
import org.scalatest.funsuite.AnyFunSuite
import io.circe.derivation.annotations.JsonCodec
import pureconfig._
import cats.syntax.all._
import io.circe.Decoder

class DataLoaderSpec extends AnyFunSuite {

  private val oreFolder = {
    val cwd = File.currentWorkingDirectory
    if ((cwd / "conf" / "application.conf").exists) cwd
    else cwd / "ore" //Assume the tests were started from IntelliJ, and we're in the root folder
  }

  private val loadersFolder = oreFolder / "test" / "data" / "loaders"
  private val testRoots     = loadersFolder.glob("testroot-*.json")

  implicit private val loaderEntryDecoder: Decoder[PluginInfoParser.Entry] = {
    import io.circe.derivation.deriveDecoder
    implicit val dependencyDecoder: Decoder[PluginInfoParser.Dependency] = deriveDecoder[PluginInfoParser.Dependency]
    deriveDecoder[PluginInfoParser.Entry]
  }

  testRoots.foreach { testRootFile =>
    val testRoot =
      io.circe.parser
        .decode[DataLoaderSpec.TestRoot](testRootFile.contentAsString)
        .fold(e => sys.error(s"Invalid test root $testRootFile: ${e.show}"), identity)

    val loader = ConfigSource.file((loadersFolder / testRoot.config).path).loadOrThrow[OreConfig.Ore.Loader]

    testRoot.tests.foreach { testObj =>
      val toTest = (loadersFolder / testObj.file).contentAsString
      val expectedEntries =
        io.circe.parser
          .decode[Seq[PluginInfoParser.Entry]]((loadersFolder / testObj.result).contentAsString)
          .fold(e => sys.error(s"Invalid test result ${loadersFolder / testObj.result}: ${e.show}"), identity)

      test(s"${testRoot.commonName} ${testObj.name}") {
        val processed = PluginInfoParser.processLoader(toTest.getBytes("UTF-8"), loader)
        val processedEntries = processed.fold(
          es => fail(s"Failed to process file:\n  ${es.mkString_("  \n")}", es.head),
          _._2
        )

        assert(processedEntries.toSet == expectedEntries.toSet)
      }
    }
  }
}
object DataLoaderSpec {

  @JsonCodec case class TestRoot(
      config: String,
      commonName: String,
      tests: List[Test]
  )

  @JsonCodec case class Test(
      name: String,
      file: String,
      result: String
  )

}
