package cats.effect
package unsafe

import cats.effect._
import cats.syntax.all._
import com.example.nativepoller.{NativePoller, EpollEvent, EventLoop, PollingSystem}

// import cats.effect.unsafe.metrics.PollerMetrics

// import java.nio.channels.{SelectableChannel, SelectionKey}
// import java.nio.channels.spi.{AbstractSelector, SelectorProvider}
// import java.util.Iterator

// import SelectorSystem._


trait Selector {

  def select(fd: Int, eventFlag: Short): IO[Int]
}


final class EpollSelectorSystem[F[_]: Async] private (
    val poller: NativePoller[F],
    val loop: EventLoop[F]
) extends PollingSystem {

  type Api = Selector

  def close(): Unit = () 

  def makeApi(ctx: PollingContext[Poller]): Selector =
    new SelectorImpl(ctx, poller, loop)

  def makePoller(): Poller =
    new Poller(poller, loop)

  def closePoller(p: Poller): Unit =
    p.close()

  def poll(p: Poller, nanos: Long): PollResult = {
    
    PollResult.Complete
  }

  def processReadyEvents(p: Poller): Boolean = {
    
    true 
  }

  def needsPoll(p: Poller): Boolean = true

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit =
    loop.wakeup()

  def metrics(p: Poller): PollerMetrics = p


  final class SelectorImpl private[SelectorSystem] (
      ctx: PollingContext[Poller],
      poller: NativePoller[F],
      loop: EventLoop[F]
  ) extends Selector {


    def select(fd: Int, eventFlag: Short): IO[Int] = IO.async { selectCb =>
      IO.async_[Option[IO[Unit]]] { cb =>
        ctx.accessPoller { _ =>
          try {
  
            val reg: F[Unit] = eventFlag match {
              case EpollEvent.EPOLLIN  => poller.registerRead(fd)
              case EpollEvent.EPOLLOUT => poller.registerWrite(fd)
              case _                   => Async[F].unit
            }

           
            val cancel: IO[Unit] =
              reg.flatMap(_ => loop.addCallback(fd, Async[F].delay(selectCb(Right(1))))).void

            cb(Right(Some(cancel)))
          } catch {
            case ex if UnsafeNonFatal(ex) =>
              cb(Left(ex))
          }
        }
      }
    }
  }

  
  final class Poller private[SelectorSystem] (
      val poller: NativePoller[F],
      val loop: EventLoop[F]
  ) extends PollerMetrics {

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

    def close(): Unit = poller.close().unsafeRunSync()

    override def toString: String = "EpollSelector"
  }
}

object EpollSelectorSystem {


  def make[F[_]: Async]: Resource[F, EpollSelectorSystem[F]] =
    for {
      poller <- Resource.eval(NativePoller.makeLinux[F])
      loop <- EventLoop.make[F]
      _ <- loop.start()
    } yield new EpollSelectorSystem[F](poller, loop)
}