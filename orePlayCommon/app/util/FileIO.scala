package util

import scala.language.higherKinds

import java.nio.file.Path

import cats.effect.{Bracket, Resource}
import cats.{Applicative, Defer, Traverse, ~>}

trait FileIO[F[_]] { self =>

  def list(path: Path): Resource[F, LazyList[Path]]

  def exists(path: Path): F[Boolean]

  def notExists(path: Path): F[Boolean]

  def isDirectory(path: Path): F[Boolean]

  def createDirectories(path: Path): F[Unit]

  def move(from: Path, to: Path): F[Unit]

  def delete(path: Path): F[Unit]

  def deleteIfExists(path: Path): F[Unit]

  def traverseLimited[G[_]: Traverse, A, B](fs: G[A])(f: A => F[B]): F[List[B]]

  def executeBlocking[A](block: => A): F[A]

  def imapK[G[_]](
      f: F ~> G,
      g: G ~> F
  )(implicit GD: Defer[G], GA: Applicative[G]): FileIO[G] =
    new FileIO[G] {
      override def list(path: Path): Resource[G, LazyList[Path]] = self.list(path).mapK(f)

      override def exists(path: Path): G[Boolean] = f(self.exists(path))

      override def notExists(path: Path): G[Boolean] = f(self.notExists(path))

      override def isDirectory(path: Path): G[Boolean] = f(self.isDirectory(path))

      override def createDirectories(path: Path): G[Unit] = f(self.createDirectories(path))

      override def move(from: Path, to: Path): G[Unit] = f(self.move(from, to))

      override def delete(path: Path): G[Unit] = f(self.delete(path))

      override def deleteIfExists(path: Path): G[Unit] = f(self.deleteIfExists(path))

      override def traverseLimited[H[_]: Traverse, A, B](fs: H[A])(h: A => G[B]): G[List[B]] =
        f(self.traverseLimited(fs)(a => g(h(a))))

      override def executeBlocking[A](block: => A): G[A] = f(self.executeBlocking(block))
    }
}
