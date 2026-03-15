package com.example.nativepoller.example

import cats.effect._
import cats.syntax.all._
import com.example.nativepoller._

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}

object EchoServer extends IOApp.Simple {

  val run: IO[Unit] =
    PollingSystem.make[IO].use { polling =>
      for {
        server <- IO.blocking {
          val ch = ServerSocketChannel.open()
          ch.bind(new InetSocketAddress("127.0.0.1", 8080))
          ch.configureBlocking(false)
          ch
        }

        serverFd <- IO(server.hashCode()) // placeholder fd mapping

        _ <- IO.println("Listening on 127.0.0.1:8080")

        _ <- acceptLoop(polling, server, serverFd)
      } yield ()
    }

  def acceptLoop(
      polling: PollingSystem[IO],
      server: ServerSocketChannel,
      fd: Int
  ): IO[Unit] =
    for {
      _ <- polling.untilReadable(fd)

      client <- IO.blocking(server.accept())

      _ <- IO(client.configureBlocking(false))

      _ <- handleClient(polling, client).start

      _ <- acceptLoop(polling, server, fd)
    } yield ()

  def handleClient(
      polling: PollingSystem[IO],
      client: SocketChannel
  ): IO[Unit] = {

    val buffer = ByteBuffer.allocate(1024)

    def loop: IO[Unit] =
      for {
        n <- IO.blocking(client.read(buffer))

        _ <-
          if (n > 0)
            for {
              _ <- IO.println(s"Echoing $n bytes")
              _ <- IO.blocking {
                buffer.flip()
                client.write(buffer)
                buffer.clear()
              }
              _ <- loop
            } yield ()
          else
            IO.blocking(client.close())
      } yield ()

    loop
  }
}