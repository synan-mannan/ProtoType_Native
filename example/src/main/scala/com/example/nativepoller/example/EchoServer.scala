package com.example.nativepoller.example

import cats.effect.*
import cats.syntax.all.*

import com.example.epollSystem.{EpollSystem, NativeEpoll}
import com.example.Selector

import jnr.ffi.{LibraryLoader, Pointer, Memory, Runtime}

object EchoServer extends IOApp.Simple {

  val EPOLLIN = NativeEpoll.EPOLLIN
  val EPOLLOUT = NativeEpoll.EPOLLOUT

  val AF_INET = 2
  val SOCK_STREAM = 1

  val SOL_SOCKET = 1
  val SO_REUSEADDR = 2

  val F_GETFL = 3
  val F_SETFL = 4
  val O_NONBLOCK = 0x800

  val PORT = 8080
  val BACKLOG = 128
  val BUF_SIZE = 1024
  val ADDR_LEN = 16

  trait TcpLibC {

    def socket(domain: Int, `type`: Int, protocol: Int): Int

    def bind(sockfd: Int, addr: Pointer, addrlen: Int): Int

    def listen(sockfd: Int, backlog: Int): Int

    def accept(sockfd: Int, addr: Pointer, addrlen: Pointer): Int

    def read(fd: Int, buf: Pointer, count: Int): Int

    def write(fd: Int, buf: Pointer, count: Int): Int

    def close(fd: Int): Int

    def setsockopt(
        fd: Int,
        level: Int,
        optname: Int,
        optval: Pointer,
        optlen: Int
    ): Int

    def fcntl(fd: Int, cmd: Int, arg: Int): Int
  }

  object TcpLibC {

    lazy val libc: TcpLibC =
      LibraryLoader
        .create(classOf[TcpLibC])
        .load("c")
  }

  val ffiRuntime: Runtime = Runtime.getRuntime(TcpLibC.libc)

  def htons(port: Int): Short =
    ((port >> 8) | ((port & 0xff) << 8)).toShort

  def setNonBlocking(fd: Int): IO[Unit] =
    IO.blocking {
      val flags = TcpLibC.libc.fcntl(fd, F_GETFL, 0)
      TcpLibC.libc.fcntl(fd, F_SETFL, flags | O_NONBLOCK)
    }.void

  def setupServer(): IO[Int] =
    IO.blocking {

      val serverFd =
        TcpLibC.libc.socket(AF_INET, SOCK_STREAM, 0)

      if (serverFd < 0)
        throw new RuntimeException("socket failed")

      val opt = Memory.allocate(ffiRuntime, 4)
      opt.putInt(0, 1)

      TcpLibC.libc.setsockopt(
        serverFd,
        SOL_SOCKET,
        SO_REUSEADDR,
        opt,
        4
      )

      val addr = Memory.allocate(ffiRuntime, ADDR_LEN)

      addr.putShort(0, AF_INET.toShort)
      addr.putShort(2, htons(PORT))

      addr.putInt(4, 0x0100007f)

      if (TcpLibC.libc.bind(serverFd, addr, ADDR_LEN) < 0)
        throw new RuntimeException("bind failed")

      if (TcpLibC.libc.listen(serverFd, BACKLOG) < 0)
        throw new RuntimeException("listen failed")

      serverFd
    }

  def acceptLoop(
      selector: Selector,
      serverFd: Int
  ): IO[Unit] = {

    val addr = Memory.allocate(ffiRuntime, ADDR_LEN)
    val len = Memory.allocate(ffiRuntime, 4)

    len.putInt(0, ADDR_LEN)

    for {
      _ <- selector.select(serverFd, EPOLLIN).void

      clientFd <- IO.blocking {
        TcpLibC.libc.accept(serverFd, addr, len)
      }

      _ <-
        if (clientFd >= 0)
          for {
            _ <- setNonBlocking(clientFd)
            _ <- handleClient(selector, clientFd).start
          } yield ()
        else IO.unit

      _ <- acceptLoop(selector, serverFd)
    } yield ()
  }

  def writeAll(
      fd: Int,
      buf: Pointer,
      remaining: Int
  ): IO[Unit] =
    if (remaining <= 0) IO.unit
    else
      IO.blocking(TcpLibC.libc.write(fd, buf, remaining)).flatMap { written =>
        if (written <= 0)
          IO.raiseError(new RuntimeException("write failed"))
        else
          writeAll(fd, buf.slice(written), remaining - written)
      }

  def handleClient(
      selector: Selector,
      clientFd: Int
  ): IO[Unit] = {

    val buf = Memory.allocate(ffiRuntime, BUF_SIZE)

    def loop: IO[Unit] =
      for {
        _ <- selector.select(clientFd, EPOLLIN)

        n <- IO.blocking {
          TcpLibC.libc.read(clientFd, buf, BUF_SIZE)
        }

        _ <-
          if (n > 0) {
            writeAll(clientFd, buf, n) >>
              selector.select(clientFd, EPOLLOUT).void >>
              loop
          } else
            IO.blocking(TcpLibC.libc.close(clientFd)).void
      } yield ()

    loop
  }

  val run: IO[Unit] =
    Selector.get.flatMap { selector =>
      for {
        serverFd <- setupServer()
        _ <- IO.println(
          "=== Native Epoll TCP Echo Server running on 127.0.0.1:8080 ==="
        )
        _ <- IO.println(
          "Test with: nc 127.0.0.1 8080"
        )
        fiber <- acceptLoop(selector, serverFd).start
        _ <- fiber.join
      } yield ()
    }
}
