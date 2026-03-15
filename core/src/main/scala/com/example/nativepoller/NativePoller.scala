package com.example.nativepoller

import cats.effect.*
import cats.syntax.all.*
import jnr.ffi.{LibraryLoader, Pointer, Runtime, Memory}
import jnr.ffi.annotations.In
import jnr.posix.POSIX
import jnr.posix.POSIXFactory


trait NativePoller[F[_]] {
  def registerRead(fd: Int): F[Unit]
  def registerWrite(fd: Int): F[Unit]
  def unregister(fd: Int): F[Unit]
  def pollEvents(timeoutMs: Int): F[List[EpollEvent]]
  def close(): F[Unit]
}

case class EpollEvent(
  events: Short,
  data: Long
)

object EpollEvent {
  val EPOLLIN  = 1.toShort
  val EPOLLOUT = 4.toShort
  val EPOLLERR = 0x8.toShort
}

object NativePoller {
  def makeLinux[F[_]: Sync]: F[NativePoller[F]] =
    EpollPollerLinux.make[F].widen
}


private class EpollPollerLinux[F[_]: Sync](
    epfd: Int,
    posix: POSIX
) extends NativePoller[F] {

  private val eventSize = 16

  def registerRead(fd: Int): F[Unit] =
    register(fd, EpollEvent.EPOLLIN)

  def registerWrite(fd: Int): F[Unit] =
    register(fd, EpollEvent.EPOLLOUT)

  def unregister(fd: Int): F[Unit] =
    Sync[F].blocking {
      LibC.libc.epoll_ctl(epfd, EpollConstants.EPOLL_CTL_DEL, fd, null)
    }.void

  private def register(fd: Int, events: Short): F[Unit] =
    Sync[F].blocking {
      val eventPtr = Memory.allocate(LibC.runtime, eventSize)

      eventPtr.putInt(0, events.toInt)
      eventPtr.putLong(8, fd.toLong)

      LibC.libc.epoll_ctl(
        epfd,
        EpollConstants.EPOLL_CTL_ADD,
        fd,
        eventPtr
      )
    }.void

  def pollEvents(timeoutMs: Int): F[List[EpollEvent]] =
    Sync[F].blocking {

      val maxEvents = 128

      val events =
        Array.fill(maxEvents)(
          Memory.allocate(LibC.runtime, eventSize)
        )

      val n =
        LibC.libc.epoll_wait(
          epfd,
          events,
          maxEvents,
          timeoutMs
        )

      if (n < 0)
        throw new RuntimeException("epoll_wait failed")

      (0 until n).toList.map { i =>
        val ptr = events(i)

        EpollEvent(
          ptr.getInt(0).toShort,
          ptr.getLong(8)
        )
      }
    }

  def close(): F[Unit] =
    Sync[F].blocking(posix.close(epfd)).void
}

object EpollPollerLinux {

  def make[F[_]: Sync]: F[EpollPollerLinux[F]] =
    for {

      posix <- Sync[F].delay(POSIXFactory.getPOSIX)

      epfd <- Sync[F].blocking(
        LibC.libc.epoll_create1(EpollConstants.EPOLL_CLOEXEC)
      ).flatMap { fd =>
        if (fd < 0)
          Sync[F].raiseError(
            new RuntimeException("epoll_create1 failed")
          )
        else
          fd.pure[F]
      }

    } yield new EpollPollerLinux[F](epfd, posix)
}

trait LibC {

  @In
  def epoll_create1(flags: Int): Int

  @In
  def epoll_ctl(
      epfd: Int,
      op: Int,
      fd: Int,
      event: Pointer
  ): Int

  @In
  def epoll_wait(
      epfd: Int,
      events: Array[Pointer],
      maxevents: Int,
      timeout: Int
  ): Int
}

object LibC {

  lazy val libc: LibC =
    LibraryLoader
      .create(classOf[LibC])
      .load("c")

  lazy val runtime: Runtime =
    Runtime.getRuntime(libc)
}

object EpollConstants {

  val EPOLL_CLOEXEC = 0x80000

  val EPOLL_CTL_ADD = 1
  val EPOLL_CTL_DEL = 2
  val EPOLL_CTL_MOD = 3
}