package com.example.epollSystem

import jnr.ffi.LibraryLoader
import jnr.ffi.Runtime
import jnr.ffi.types._
import jnr.ffi.Pointer
import jnr.ffi.Struct

import java.nio.ByteBuffer
import java.nio.ByteOrder
import cats.instances.byte
import javax.print.DocFlavor.BYTE_ARRAY

class EpollEventStruct(runtime: Runtime) extends Struct(runtime) {
  val events = new Unsigned32()
  val data = new Unsigned64()
}

case class EpollEvent(fd: Int, events: Int)

trait LibC {
  def epoll_create1(flags: Int): Int
  def epoll_ctl(epfd: Int, op: Int, fd: Int, event: Pointer): Int
  def epoll_wait(epfd: Int, events: Pointer, maxevents: Int, timeout: Int): Int

  def eventfd(initval: Int, flags: Int): Int

  def read(fd: Int, buf: Array[Byte], count: Long): Long
  def write(fd: Int, buf: Array[Byte], count: Long): Long

  def close(fd: Int): Int
}

object NativeEpoll {

  private val libc =
    LibraryLoader.create(classOf[LibC]).load("c")

  private val runtime = Runtime.getRuntime(libc)

  val EPOLLIN = 0x001
  val EPOLLOUT = 0x004
  val EPOLLERR = 0x008
  val EPOLLHUP = 0x010

  private val EPOLL_CTL_ADD = 1
  private val EPOLL_CTL_DEL = 2
  private val EPOLL_CTL_MOD = 3

  private val EFD_NONBLOCK = 0x800

  private val MAX_EVENTS = 1024

  // size of one struct
  private val EVENT_SIZE = Struct.size(new EpollEventStruct(runtime))

  def create(): Int = {
    val fd = libc.epoll_create1(0)
    if (fd < 0) throw new RuntimeException("epoll_create1 failed")
    fd
  }

  def createEventFd(): Int = {
    val fd = libc.eventfd(0, EFD_NONBLOCK)
    if (fd < 0) throw new RuntimeException("eventfd failed")
    fd
  }

  def add(fd: Int, events: Int, epollFd: Int): Unit = {
    val evStruct = new EpollEventStruct(runtime)
    evStruct.events.set(events)
    evStruct.data.set(fd.toLong)

    val res = libc.epoll_ctl(
      epollFd,
      EPOLL_CTL_ADD,
      fd,
      Struct.getMemory(evStruct)
    )

    if (res != 0) throw new RuntimeException("epoll_ctl ADD failed")
  }

  def ctlAddOrMod(epollFd: Int, fd: Int, events: Int): Unit = {
    val evStruct = new EpollEventStruct(runtime)
    evStruct.events.set(events)
    evStruct.data.set(fd.toLong)

    val ptr = Struct.getMemory(evStruct)

    val addRes = libc.epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, ptr)

    if (addRes != 0) {
      val modRes = libc.epoll_ctl(epollFd, EPOLL_CTL_MOD, fd, ptr)
      if (modRes != 0)
        throw new RuntimeException("epoll_ctl ADD/MOD failed")
    }
  }

  def ctlDel(epollFd: Int, fd: Int): Unit = {
    val res = libc.epoll_ctl(epollFd, EPOLL_CTL_DEL, fd, null)
    if (res != 0)
      throw new RuntimeException(s"epoll_ctl DEL failed for fd=$fd")
  }

  def wait(epollFd: Int, timeout: Int): Int = {
    val eventsMem =
      jnr.ffi.Memory.allocate(runtime, EVENT_SIZE * MAX_EVENTS)

    libc.epoll_wait(epollFd, eventsMem, MAX_EVENTS, timeout)
  }

  def drain(epollFd: Int): Array[EpollEvent] = {
    val eventsMem =
      jnr.ffi.Memory.allocate(runtime, EVENT_SIZE * MAX_EVENTS)

    val n = libc.epoll_wait(epollFd, eventsMem, MAX_EVENTS, 0)

    val result = new Array[EpollEvent](n)

    var i = 0
    while (i < n) {
      val evStruct = new EpollEventStruct(runtime)

      // map struct to correct memory slice
      evStruct.useMemory(eventsMem.slice(i * EVENT_SIZE, EVENT_SIZE))

      result(i) = EpollEvent(
        evStruct.data.get().toInt,
        evStruct.events.get().toInt
      )

      i += 1
    }

    result
  }

  def wakeup(eventFd: Int): Unit = {
    val bb = ByteBuffer.allocate(8)
    bb.order(ByteOrder.nativeOrder())
    bb.putLong(1L)

    val res = libc.write(eventFd, bb.array(), 8)
    if (res < 0)
      throw new RuntimeException("eventfd write failed")
  }

  def clearEventFd(eventFd: Int): Unit = {
    val buf = new Array[Byte](8)
    val res = libc.read(eventFd, buf, 8)
    if (res < 0)
      throw new RuntimeException("eventfd read failed")
  }

  def close(fd: Int): Unit = {
    val res = libc.close(fd)
    if (res != 0)
      throw new RuntimeException(s"close failed for fd=$fd")
  }
}
