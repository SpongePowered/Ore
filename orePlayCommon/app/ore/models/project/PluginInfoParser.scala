package ore.models.project

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.util.jar.JarInputStream

import scala.annotation.nowarn
import scala.jdk.CollectionConverters._
import scala.util.Try

import ore.OreConfig
import ore.OreConfig.Ore.Loader
import ore.OreConfig.Ore.Loader._

import _root_.io.circe._
import _root_.io.circe.syntax._
import cats.data.{Ior, NonEmptyList, Validated, ValidatedNel}
import cats.instances.list._
import cats.syntax.all._
import shapeless.tag
import shapeless.tag.@@
import toml.Toml

object PluginInfoParser {

  case class Dependency(
      identifier: String,
      rawVersion: Option[String],
      required: Boolean
  )
  case class PartialEntry(
      name: Option[String],
      identifier: String,
      version: Option[String],
      dependencies: Set[Dependency]
  )
  case class Entry(
      name: String,
      identifier: String,
      version: String,
      dependencies: Set[Dependency],
      mixin: Boolean
  )

  def processJar(jar: JarInputStream)(implicit config: OreConfig): (List[String], List[Entry]) = {
    val filesOfInterest =
      config.ore.loaders.values.flatMap(loader => loader.filename.map(loader -> _).toList).groupMap(_._2)(_._1)

    val res = Iterator
      .continually(jar.getNextJarEntry)
      .takeWhile(_ != null) // scalafix:ok
      .filter(entry => filesOfInterest.contains(entry.getName))
      .flatMap { entry =>
        val bytes = Iterator.continually(jar.read()).takeWhile(_ != -1).map(_.toByte).toArray
        filesOfInterest(entry.getName).map(processLoader(bytes, _))
      }
      .toList

    //Need to handle the manifest seperately
    val metaInfRes = filesOfInterest.get("META-INF/MANIFEST.MF").toList.flatMap { manifestLoaders =>
      val baos = new ByteArrayOutputStream()
      jar.getManifest.write(baos)
      val bytes = baos.toByteArray
      manifestLoaders.map(processLoader(bytes, _))
    }

    val (userErrors, entries) = (res ::: metaInfRes).map {
      case Validated.Valid((errors, entries)) => (errors, entries)
      case Validated.Invalid(errors)          => (errors.map(_.show).toList, Nil)
    }.unzip

    (userErrors.flatten, entries.flatten)
  }

  def isMixin(jar: JarInputStream): Boolean =
    Option(jar.getManifest.getMainAttributes.getValue("MixinConfigs")).isDefined

  def tomlScalaToCirce(value: toml.Value): Json = value match {
    case toml.Value.Str(value)  => Json.fromString(value)
    case toml.Value.Bool(value) => Json.fromBoolean(value)
    case toml.Value.Real(value) => Json.fromDoubleOrString(value)
    case toml.Value.Num(value)  => Json.fromLong(value)
    case toml.Value.Tbl(value)  => Json.obj(value.map(t => t._1 -> tomlScalaToCirce(t._2)).toSeq: _*)
    case toml.Value.Arr(value)  => Json.arr(value.map(tomlScalaToCirce): _*)
    //No idea when thse will play in
    case toml.Value.Date(value)           => Json.fromString(value.toString)
    case toml.Value.Time(value)           => Json.fromString(value.toString)
    case toml.Value.DateTime(value)       => Json.fromString(value.toString)
    case toml.Value.OffsetDateTime(value) => Json.fromString(value.toString)
  }

  def processLoader(bytes: Array[Byte], loader: Loader): ValidatedNel[Error, (Seq[String], Seq[Entry])] = {
    lazy val strContent = new String(bytes, "UTF-8")

    val json: Either[ParsingFailure, Json] = loader.dataType match {
      case DataType.JSON => parser.parse(strContent)
      case DataType.YAML => yaml.parser.parse(strContent)
      case DataType.Manifest =>
        Try {
          val attributes = new java.util.jar.Manifest(new ByteArrayInputStream(bytes)).getMainAttributes
          Right(Json.obj(attributes.asScala.map(t => t._1.toString := t._2.toString).toSeq: _*))
        }.recover {
          case e: IOException => Left(ParsingFailure("Could not parse jar manifest", e))
        }.get
      case DataType.TOML =>
        Toml.parse(strContent).map(tomlScalaToCirce).leftMap {
          case (at, message) =>
            ParsingFailure(s"""|Parse errors at: ${at.mkString(", ")}
                               |$message""".stripMargin, new Exception(message))
        }
    }

    Validated.fromEither(json).leftMap(NonEmptyList.one).andThen(json => parseLoaderData(json.hcursor, loader))
  }

  def parseLoaderData(data: HCursor, loader: Loader): ValidatedNel[DecodingFailure, (Seq[String], Seq[Entry])] = {
    def parseField(
        cursor: ACursor,
        field: Field,
        name: Option[String] = None,
        identifier: Option[String] = None,
        version: Option[String] = None
    ): Seq[Decoder.Result[ACursor]] @@ NonDeterministic =
      tag[NonDeterministic](
        field.map { path =>
          val startAtRoot = path.head == "$"
          val pathStart   = if (startAtRoot) path.tail else path.toList
          pathStart.foldLeft(Right(if (startAtRoot) data else cursor): Decoder.Result[ACursor])((c, s) =>
            c.flatMap {
              cursor =>
                @nowarn
                val fieldToGoDown = s match {
                  case "$identifier" =>
                    identifier.toRight(DecodingFailure("Identifier unknown at this location", cursor.history))
                  case "$name" =>
                    name.toRight(DecodingFailure("Name unknown at this location", cursor.history))
                  case "$version" =>
                    version.toRight(DecodingFailure("Version unknown at this location", cursor.history))
                  case _ => Right(s)
                }

                fieldToGoDown.map(cursor.downField)
            }
          )
        }
      )

    def getFieldOptional(
        cursor: ACursor,
        field: Field,
        name: Option[String] = None,
        identifier: Option[String] = None,
        version: Option[String] = None
    ): Seq[ACursor] @@ NonDeterministic =
      tag[NonDeterministic](parseField(cursor, field, name, identifier, version).flatMap(_.toOption))

    def getFieldRequired(
        cursor: ACursor,
        field: Field
    ): ValidatedNel[DecodingFailure, NonEmptyList[ACursor] @@ NonDeterministic] = {
      val parsedFields   = parseField(cursor, field)
      val ignoringErrors = parsedFields.flatMap(_.toOption)

      if (ignoringErrors.isEmpty && field.nonEmpty)
        Validated.invalid(NonEmptyList.fromListUnsafe(parsedFields.flatMap(_.swap.toOption).toList))
      else
        Validated.validNel(tag[NonDeterministic](NonEmptyList.fromListUnsafe(ignoringErrors.toList)))
    }

    def loadEntry(json: JsonObject): Option[PartialEntry] = {
      val cursor = Json.fromJsonObject(json).hcursor

      def getEntryField[A: Decoder](
          cursor: ACursor,
          field: Field,
          findFilter: A => Boolean = (_: A) => true,
          allowReferences: Boolean = true
      ): Option[A] =
        getFieldOptional(
          cursor,
          field,
          if (!allowReferences) None else name,
          if (!allowReferences) None else identifier,
          if (!allowReferences) None else version
        ).view
          .map(_.as[A])
          .flatMap(_.toOption)
          .find(findFilter)

      lazy val name       = getEntryField[String](cursor, loader.nameField, _.nonEmpty, allowReferences = false)
      lazy val identifier = getEntryField[String](cursor, loader.identifierField, _.nonEmpty, allowReferences = false)
      lazy val version    = getEntryField[String](cursor, loader.versionField, _.nonEmpty, allowReferences = false)

      val dependencies = for {
        depBlock    <- loader.dependencyTypes
        arrayCursor <- getFieldOptional(cursor, depBlock.field, name, identifier, version)
        depSyntax = depBlock.dependencySyntax
        arrayObj <- arrayCursor.values.toList.flatten
        cursor = arrayObj.hcursor
        dependency <- depSyntax match {
          case DependencyBlock.DependencySyntax.AsObject(identifierField, versionField, requiredField, optionalField) =>
            val identifierFieldTyped = tag[NonDeterministic](identifierField.toList)

            val identifier = getEntryField[String](cursor, identifierFieldTyped, _.nonEmpty)
            val version    = getEntryField[String](cursor, versionField, _.nonEmpty)
            val required   = getEntryField[Boolean](cursor, requiredField)
            val optional   = getEntryField[Boolean](cursor, optionalField)

            identifier.map { id =>
              Dependency(
                id,
                version,
                required.orElse(optional.map(!_)).getOrElse(depBlock.defaultIsRequired)
              )
            }.toList

          case DependencyBlock.DependencySyntax.AtSeparated =>
            cursor.as[String].map(_.split("@", 2)).map(a => (a(0), a.lift(1))).toOption.toList.map {
              case (id, version) =>
                Dependency(id, version, depBlock.defaultIsRequired)
            }
        }
      } yield dependency

      val groupedDependencies = dependencies.groupMapReduce(_.identifier)(identity) { (d1, d2) =>
        val versionToUse = (d1.rawVersion, d2.rawVersion) match {
          case (None, None)                     => None
          case (None, opt @ Some(_))            => opt
          case (opt @ Some(_), None)            => opt
          case (Some(v1), Some(v2)) if v1 == v2 => Some(v1)
          case (Some(v1), Some(v2)) =>
            val v1Parts = v1.split('.').toVector
            val v2Parts = v2.split('.').toVector

            v1Parts.align(v2Parts).foldLeft(None: Option[String]) {
              case (victor @ Some(_), _) => victor
              case (None, Ior.Both(a, b)) =>
                cats.Order[Option[Int]]
                a.toIntOption.compare(b.toIntOption) match {
                  case 0          => None
                  case i if i > 0 => Some(v1)
                  case i if i < 0 => Some(v2)
                }

              case (None, Ior.Left(_))  => Some(v1)
              case (None, Ior.Right(_)) => Some(v2)
            }

        }

        Dependency(d1.identifier, versionToUse, d1.required || d2.required)
      }

      identifier.map(id => PartialEntry(name, id, version, groupedDependencies.values.toSet))
    }

    val entryCursors = loader.entryLocation match {
      case OreConfig.Ore.Loader.EntryLocation.Root         => Validated.validNel(NonEmptyList.one(data))
      case OreConfig.Ore.Loader.EntryLocation.Field(field) => getFieldRequired(data, field)
    }

    entryCursors
      .andThen { cursors =>
        val entryObjs = tag[NonDeterministic](
          loader.rootType match {
            case RootType.AlwaysObject => cursors.map(_.as[JsonObject].map(Seq(_)))
            case RootType.AlwaysList   => cursors.map(c => c.as[Seq[JsonObject]])
            case RootType.ObjectOrList => cursors.map(c => c.as[Seq[JsonObject]].orElse(c.as[JsonObject].map(Seq(_))))
          }
        )

        val ignoringErrors = entryObjs.toList.flatMap(_.toOption)
        if (ignoringErrors.isEmpty)
          Validated.invalid(NonEmptyList.fromListUnsafe(entryObjs.toList.flatMap(_.swap.toOption)))
        else
          Validated.validNel(tag[NonDeterministic](NonEmptyList.fromListUnsafe(ignoringErrors)))
      }
      .map { entryChoices =>
        val results = entryChoices.toList.flatMap(_.flatMap(loadEntry))
        val userErrorsAndEntries =
          results
            .groupMapReduce(_.identifier)(identity)((p1, p2) =>
              PartialEntry(
                p1.name.orElse(p2.name),
                p1.identifier,
                p1.version.orElse(p2.version),
                p1.dependencies ++ p2.dependencies
              )
            )
            .values
            .map {
              case PartialEntry(Some(name), identifier, Some(version), dependencies) =>
                Right(Entry(name, identifier, version, dependencies, mixin = false))
              case PartialEntry(None, identifier, _, _) => Left(s"No name found for entry with identifier $identifier")
              case PartialEntry(_, identifier, None, _) =>
                Left(s"No version found for entry with identifier $identifier")
            }
            .toSeq

        userErrorsAndEntries.toList.partitionEither(identity)
      }
  }
}
