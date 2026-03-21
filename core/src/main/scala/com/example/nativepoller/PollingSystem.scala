package com.example.nativepoller

import cats.effect.syntax.all._
import cats.effect.*
import cats.syntax.all.*
import com.example.nativepoller.{NativePoller, EventLoop, EpollEvent}

trait PollingSystem[F[_]] {
  def untilReadable(fd: Int): F[Unit]
  def untilWritable(fd: Int): F[Unit]
}

class EpollPollingSystem[F[_]: Async](
    val poller: NativePoller[F],
    val loop: EventLoop[F]
) extends PollingSystem[F] {

  def untilReadable(fd: Int): F[Unit] = await(fd, EpollEvent.EPOLLIN)

  def untilWritable(fd: Int): F[Unit] = await(fd, EpollEvent.EPOLLOUT)

  private def await(fd: Int, eventFlag: Short): F[Unit] = Async[F].async { k =>
    val cbF = Sync[F].defer(k(Right(())))
    val regF =
      if (eventFlag == EpollEvent.EPOLLIN) poller.registerRead(fd)
      else poller.registerWrite(fd)
    regF
      .flatMap { _ =>
        val cbId = loop.addCallback(fd, eventFlag, cbF)
        Sync[F].pure(cbId)
      }
      .map { cbId =>
        Some(loop.tryCancel(fd, cbId).void)
      }
  }
}

object PollingSystem {
  def make[F[_]: Async]: Resource[F, PollingSystem[F]] =
    for {
      poller <- Resource.make(NativePoller.makeLinux[F])(_.close())
      eventLoop <- EventLoop.make(poller)
      _ <- eventLoop.start()
    } yield new EpollPollingSystem(poller, eventLoop)
}
