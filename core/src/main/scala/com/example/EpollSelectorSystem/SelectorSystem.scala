package cats.effect
package unsafe

import cats.effect.unsafe.metrics.PollerMetrics
import cats.syntax.all._

import java.util.concurrent.ConcurrentHashMap

final class EpollSystem private () extends PollingSystem {

  type Api = Selector

  def close(): Unit = ()

  def makeApi(ctx: PollingContext[Poller]): Selector =
    new SelectorImpl(ctx)

  def makePoller(): Poller = {
    val epollFd = NativeEpoll.create()
    val eventFd = NativeEpoll.createEventFd()

    // register wakeup fd
    NativeEpoll.add(eventFd, NativeEpoll.EPOLLIN, epollFd)

    new Poller(epollFd, eventFd)
  }

  def closePoller(poller: Poller): Unit = {
    NativeEpoll.close(poller.epollFd)
    NativeEpoll.close(poller.eventFd)
  }

  def poll(poller: Poller, nanos: Long): PollResult = {
    val timeout =
      if (nanos < 0) -1
      else (nanos / 1000000).toInt

    val n = NativeEpoll.wait(poller.epollFd, timeout)

    if (n > 0) PollResult.Complete
    else PollResult.Interrupted
  }

  def processReadyEvents(poller: Poller): Boolean = {
    val events = NativeEpoll.drain(poller.epollFd)

    var rescheduled = false

    var i = 0
    while (i < events.length) {
      val ev = events(i)
      val fd = ev.fd
      val ready = ev.events

      // ignore wakeup fd
      if (fd == poller.eventFd) {
        NativeEpoll.clearEventFd(fd)
      } else {
        val callbacks = poller.callbacks.get(fd)

        if (callbacks != null) {
          val iter = callbacks.iterator()

          while (iter.hasNext) {
            val node = iter.next()

            if ((node.interest & ready) != 0) {
              node.remove()

              val cb = node.callback
              if (cb != null) {
                cb(Right(ready))
                rescheduled = true
                poller.countSucceededOperation(ready)
              } else {
                poller.countCanceledOperation(node.interest)
              }
            }
          }
        }
      }

      i += 1
    }

    rescheduled
  }

  def needsPoll(poller: Poller): Boolean =
    !poller.callbacks.isEmpty

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit =
    NativeEpoll.wakeup(targetPoller.eventFd)

  def metrics(poller: Poller): PollerMetrics = poller


  final class SelectorImpl(
      ctx: PollingContext[Poller]
  ) extends Selector {

    def select(fd: Int, ops: Int): IO[Int] =
      IO.async { selectCb =>
        IO.async_[Option[IO[Unit]]] { cb =>
          ctx.accessPoller { poller =>
            try {
              poller.countSubmittedOperation(ops)

              val node =
                poller.register(fd, ops, selectCb)

              val cancel = IO {
                if (ctx.ownPoller(poller)) {
                  poller.countCanceledOperation(ops)
                  node.remove()
                } else {
                  node.clear()
                }
              }

              cb(Right(Some(cancel)))
            } catch {
              case ex if UnsafeNonFatal(ex) =>
                poller.countErroredOperation(ops)
                cb(Left(ex))
            }
          }
        }
      }
  }


  final class Poller(
      val epollFd: Int,
      val eventFd: Int
  ) extends PollerMetrics {

    val callbacks =
      new ConcurrentHashMap[Int, Callbacks]()

    def register(
        fd: Int,
        ops: Int,
        cb: Either[Throwable, Int] => Unit
    ): Callbacks#Node = {

      val cbs = callbacks.computeIfAbsent(fd, _ => new Callbacks)

      NativeEpoll.ctlAddOrMod(epollFd, fd, ops)

      cbs.append(ops, cb)
    }



    private[this] var outstandingOperations: Int = 0
    private[this] var submittedOperations: Long = 0
    private[this] var succeededOperations: Long = 0
    private[this] var erroredOperations: Long = 0
    private[this] var canceledOperations: Long = 0

    def countSubmittedOperation(ops: Int): Unit = {
      outstandingOperations += 1
      submittedOperations += 1
    }

    def countSucceededOperation(ops: Int): Unit = {
      outstandingOperations -= 1
      succeededOperations += 1
    }

    def countErroredOperation(ops: Int): Unit = {
      outstandingOperations -= 1
      erroredOperations += 1
    }

    def countCanceledOperation(ops: Int): Unit = {
      outstandingOperations -= 1
      canceledOperations += 1
    }

    def operationsOutstandingCount(): Int = outstandingOperations
    def totalOperationsSubmittedCount(): Long = submittedOperations
    def totalOperationsSucceededCount(): Long = succeededOperations
    def totalOperationsErroredCount(): Long = erroredOperations
    def totalOperationsCanceledCount(): Long = canceledOperations

    override def toString: String = "Epoll"
  }
}


object EpollSystem {

  def apply(): EpollSystem =
    new EpollSystem()

  final class Callbacks {

    private var head: Node = null
    private var last: Node = null

    def append(
        interest: Int,
        callback: Either[Throwable, Int] => Unit
    ): Node = {
      val node = new Node(interest, callback)

      if (last != null) {
        last.next = node
        node.prev = last
      } else {
        head = node
      }

      last = node
      node
    }

    def iterator(): java.util.Iterator[Node] =
      new java.util.Iterator[Node] {
        private var cur = head

        def hasNext: Boolean = cur != null

        def next(): Node = {
          val n = cur
          cur = cur.next
          n
        }
      }

    final class Node(
        var interest: Int,
        var callback: Either[Throwable, Int] => Unit
    ) {
      var prev: Node = null
      var next: Node = null

      def remove(): Unit = {
        if (prev != null) prev.next = next
        else head = next

        if (next != null) next.prev = prev
        else last = prev
      }

      def clear(): Unit = {
        interest = -1
        callback = null
      }
    }
  }
}
