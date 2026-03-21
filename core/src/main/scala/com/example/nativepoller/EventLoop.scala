package com.example.nativepoller

import cats.effect.syntax.all._
import cats.effect.*
import cats.syntax.all.*
import com.example.nativepoller.EpollEvent.*
import cats.effect.kernel.Spawn

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

class EventLoop[F[_]: Async](poller: NativePoller[F]) {

  private val callbacks =
    new ConcurrentHashMap[Int, ConcurrentLinkedQueue[(Short, Long, F[Unit])]]()

  private[this] val cbCounter = new AtomicLong(0L)

  def addCallback(fd: Int, interest: Short, cb: F[Unit]): Long = {
    val cbId = cbCounter.incrementAndGet()
    val queue = callbacks.computeIfAbsent(
      fd,
      _ => new ConcurrentLinkedQueue[(Short, Long, F[Unit])]()
    )
    queue.add((interest, cbId, cb))
    cbId
  }

  def tryCancel(fd: Int, cbId: Long): F[Boolean] = Sync[F].delay {
    Option(callbacks.get(fd)).exists { queue =>
      val it = queue.iterator()
      var found = false
      while (it.hasNext() && !found) {
        val entry = it.next()
        if (entry._2 == cbId) {
          it.remove()
          found = true
        }
      }
      found
    }
  }

  private def processEvents(events: List[EpollEvent]): F[Unit] = {
    val toStart = mutable.ListBuffer.empty[F[Unit]]
    events.foreach { ev =>
      val fd = ev.data.toInt
      Option(callbacks.get(fd)).foreach { queue =>
        val it = queue.iterator()
        while (it.hasNext()) {
          val (interest, _, cb) = it.next()
          if ((interest & ev.events) != 0) {
            it.remove()
            toStart += Async[F].start(cb).void
          }
        }
        if (queue.isEmpty()) {
          callbacks.remove(fd)
        }
      }
    }
    toStart.toList.traverse_(identity)
  }

  private val runLoop: F[Unit] =
    poller.pollEvents(-1).flatMap(processEvents) >> Async[F].cede >> runLoop

  def start(): Resource[F, Fiber[F, Throwable, Unit]] =
    Resource.make(Sync[F].start(runLoop))(_.cancel)
  def stop(): F[Unit] = Async[F].unit
}

object EventLoop {
  def make[F[_]: Async](poller: NativePoller[F]): Resource[F, EventLoop[F]] =
    Resource.pure(new EventLoop[F](poller))
}
