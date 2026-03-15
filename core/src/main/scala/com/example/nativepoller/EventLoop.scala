package com.example.nativepoller

import cats.effect.syntax.all._
import cats.effect.*
import cats.syntax.all.*
import com.example.nativepoller.EpollEvent.*
import cats.effect.kernel.Spawn

import java.util.concurrent.ConcurrentHashMap

/**
  * Dedicated event loop thread that polls epoll and resumes Cats Effect fibers.
  * Maps FD -> callback to resume waiting fibers.
  */
class EventLoop[F[_]: Async](poller: NativePoller[F]) {

  private val callbacks = new ConcurrentHashMap[Int, List[F[Unit]]]()

  def addCallback(fd: Int, cb: F[Unit]): Unit = 
    callbacks.put(fd, Option(callbacks.get(fd)).getOrElse(List.empty[F[Unit]]) :+ cb)

  private def processEvents(events: List[EpollEvent]): F[Unit] = 
    events.traverse_ { ev =>
      val fd = ev.data.toInt
      Option(callbacks.remove(fd)).foreach { cbs =>
        Async[F].start(cbs.head) // prototype single cb
      }
      Async[F].unit
    }

  private val runLoop: F[Unit] = 
    poller.pollEvents(-1).flatMap(processEvents) >> Async[F].cede >> runLoop

  def start(): Resource[F, Fiber[F, Throwable, Unit]] = Resource.make(Sync[F].start(runLoop))(_.cancel)
  def stop(): F[Unit] = Async[F].unit // simplified
}

object EventLoop {
  def make[F[_]: Async]: Resource[F, EventLoop[F]] = 
    for {
      poller <- Resource.make(NativePoller.makeLinux[F])(_.close())
      loop = new EventLoop[F](poller)
    } yield loop
}

