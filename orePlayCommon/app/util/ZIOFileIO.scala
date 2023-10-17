package util

import scala.language.higherKinds

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import ore.OreConfig

import cats.Traverse
import cats.effect.Resource
import cats.syntax.all._
import zio.ZIO
import zio.blocking._
import zio.interop.catz._

class ZIOFileIO(nioBlockingFibers: Int) extends FileIO[ZIO[Blocking, Throwable, *]] {

  type BlockIO[A] = ZIO[Blocking, Throwable, A]

  override def list(path: Path): Resource[BlockIO, LazyList[Path]] =
    Resource.fromAutoCloseable(effectBlocking(Files.list(path))).map(_.iterator.asScala.to(LazyList))

  override def exists(path: Path): BlockIO[Boolean] = effectBlocking(Files.exists(path))

  override def notExists(path: Path): BlockIO[Boolean] = effectBlocking(Files.notExists(path))

  override def isDirectory(path: Path): BlockIO[Boolean] = effectBlocking(Files.isDirectory(path))

  override def createDirectories(path: Path): BlockIO[Unit] = effectBlocking {
    Files.createDirectories(path)
    ()
  }

  override def move(from: Path, to: Path): BlockIO[Unit] = effectBlocking {
    Files.move(from, to)
    ()
  }

  override def delete(path: Path): BlockIO[Unit] = effectBlocking(Files.delete(path))

  override def deleteIfExists(path: Path): BlockIO[Unit] = effectBlocking {
    Files.deleteIfExists(path)
    ()
  }

  override def traverseLimited[G[_]: Traverse, A, B](fs: G[A])(f: A => BlockIO[B]): BlockIO[List[B]] =
    ZIO.foreachParN(nioBlockingFibers)(fs.toList)(f)

  override def executeBlocking[A](block: => A): BlockIO[A] = effectBlocking(block)
}
object ZIOFileIO {
  def apply(config: OreConfig): ZIOFileIO = new ZIOFileIO(config.performance.nioBlockingFibers)
}
