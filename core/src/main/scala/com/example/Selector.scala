package com.example

import cats.effect.IO
import cats.syntax.all._

/** A low-level abstraction for asynchronous I/O readiness based on file
  * descriptors.
  *
  * This interface is designed to work with native polling mechanisms such as
  * epoll, allowing fibers to suspend until a file descriptor becomes ready for
  * a given set of operations (e.g., read or write).
  */
trait Selector {

  /** Suspends the calling fiber until the given file descriptor is ready for at
    * least one of the specified operations.
    *
    * @param fd
    *   the file descriptor to monitor
    * @param ops
    *   the interest set (e.g., EPOLLIN, EPOLLOUT)
    * @return
    *   an effect that completes with the ready operations
    */
  def select(fd: Int, ops: Int): IO[Int]

}

object Selector {

  /** Attempts to find a Selector instance installed in the current runtime.
    */
  def find: IO[Option[Selector]] =
    IO.pollers.map(_.collectFirst { case selector: Selector => selector })

  /** Retrieves the active Selector or raises an error if none is available.
    */
  def get: IO[Selector] =
    find.flatMap(
      _.liftTo[IO](
        new RuntimeException(
          "No epoll-based Selector installed in this IORuntime"
        )
      )
    )
}
