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
  def wakeup(): F[Unit]
  def pollEvents(timeoutMs: Int): F[List[EpollEvent]]
  def close(): F[Unit]
}

case class EpollEvent(
    events: Short,
    data: Long
)

object EpollEvent {
  val EPOLLIN = 1.toShort
  val EPOLLOUT = 4.toShort
  val EPOLLERR = 0x8.toShort
}

object NativePoller {
  def makeLinux[F[_]: Sync]: F[NativePoller[F]] =
    EpollPollerLinux.make[F].widen
}

private class EpollPollerLinux[F[_]: Sync](
    epfd: Int,
    posix: POSIX,
    eventFd: Int
) extends NativePoller[F] {

  def wakeup(): F[Unit] = Sync[F].blocking {
    val buf = Memory.allocate(LibC.runtime, 8)
    buf.putLong(0, 1L)
    if (LibC.libc.write(eventFd, buf, 8L) != 8L) {
      throw new RuntimeException("eventfd write failed")
    }
  }.void

  private val eventSize = 16

  def registerRead(fd: Int): F[Unit] =
    register(fd, EpollEvent.EPOLLIN) >> wakeup()

  def registerWrite(fd: Int): F[Unit] =
    register(fd, EpollEvent.EPOLLOUT) >> wakeup()

  def unregister(fd: Int): F[Unit] =
    Sync[F].blocking {
      LibC.libc.epoll_ctl(epfd, EpollConstants.EPOLL_CTL_DEL, fd, null)
    }.void

  private def register(fd: Int, events: Short): F[Unit] =
    Sync[F].blocking {
      val eventSize = 16

      // Try ADD first, then MOD if EEXIST
      val eventPtr = Memory.allocate(LibC.runtime, eventSize)
      eventPtr.putShort(0, events)
      eventPtr.putLong(8, fd.toLong)

      if (
        LibC.libc.epoll_ctl(
          epfd,
          EpollConstants.EPOLL_CTL_ADD,
          fd,
          eventPtr
        ) == 0
      ) {
        ()
      } else {
        // Assume EEXIST, do MOD
        eventPtr.putShort(0, events) // |= if needed, but simple replace for now
        if (
          LibC.libc.epoll_ctl(
            epfd,
            EpollConstants.EPOLL_CTL_MOD,
            fd,
            eventPtr
          ) != 0
        ) {
          throw new RuntimeException("epoll_ctl ADD/MOD failed for fd " + fd)
        }
      }
    }.void

  def pollEvents(timeoutMs: Int): F[List[EpollEvent]] =
    Sync[F].blocking {
      val eventSize = 16

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

      val userEvents = (0 until n).toList.flatMap { i =>
        val ptr = events(i)

        val ev = EpollEvent(
          ptr.getShort(0),
          ptr.getLong(8)
        )
        if (ev.data.toInt == eventFd) {
          // Clear wakeup event
          val clearBuf = Memory.allocate(LibC.runtime, 8)
          LibC.libc.read(eventFd, clearBuf, 8L)
          Nil
        } else {
          List(ev)
        }
      }
      userEvents
    }

  def close(): F[Unit] =
    Sync[F].blocking(posix.close(epfd)).void
}

object EpollPollerLinux {

  def make[F[_]: Sync]: F[EpollPollerLinux[F]] =
    for {
      posix <- Sync[F].delay(POSIXFactory.getPOSIX)

      epfd <- Sync[F]
        .blocking(
          LibC.libc.epoll_create1(EpollConstants.EPOLL_CLOEXEC)
        )
        .flatMap { fd =>
          if (fd < 0)
            Sync[F].raiseError(
              new RuntimeException("epoll_create1 failed")
            )
          else
            fd.pure[F]
        }

      eventFd <- Sync[F]
        .blocking {
          LibC.libc.eventfd(0L, EpollConstants.EFD_CLOEXEC)
        }
        .flatMap { fd =>
          if (fd < 0)
            Sync[F].raiseError(new RuntimeException("eventfd creation failed"))
          else
            fd.pure[F]
        }

      _ <- Sync[F].blocking {
        val evSize = 16
        val evPtr = Memory.allocate(LibC.runtime, evSize)
        evPtr.putShort(0, EpollEvent.EPOLLIN)
        evPtr.putLong(8, eventFd.toLong)
        if (
          LibC.libc.epoll_ctl(
            epfd,
            EpollConstants.EPOLL_CTL_ADD,
            eventFd,
            evPtr
          ) != 0
        ) {
          throw new RuntimeException("register eventfd failed")
        }
      }

    } yield new EpollPollerLinux[F](epfd, posix, eventFd)
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

  @In
  def eventfd(initval: Long, flags: Int): Int

  @In
  def read(fd: Int, buf: Pointer, count: Long): Long

  @In
  def write(fd: Int, buf: Pointer, count: Long): Long
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
  val EFD_CLOEXEC = 0x80000
  val EPOLLET = 0x80000000
  val EPOLLONESHOT = 0x40000000
}
