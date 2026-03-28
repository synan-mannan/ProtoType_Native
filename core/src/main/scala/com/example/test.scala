package com.example

import com.example.epollSystem.*

object TestEpoll {

  def main(args: Array[String]): Unit = {

    val epollFd = NativeEpoll.create()
    val eventFd = NativeEpoll.createEventFd()

    println(s"epollFd = $epollFd, eventFd = $eventFd")

    // Register eventfd for read events
    NativeEpoll.ctlAddOrMod(epollFd, eventFd, NativeEpoll.EPOLLIN)

    println("Registered eventfd. Triggering wakeup...")

    // Trigger event
    NativeEpoll.wakeup(eventFd)

    // Wait for event
    val events = NativeEpoll.drain(epollFd)

    println(s"Events received: ${events.length}")

    events.foreach { ev =>
      println(s"FD: ${ev.fd}, EVENTS: ${ev.events}")

      if (ev.fd == eventFd) {
        println("eventfd triggered correctly!")

        NativeEpoll.clearEventFd(eventFd)
      }
    }

    NativeEpoll.close(eventFd)
    NativeEpoll.close(epollFd)
  }
}
