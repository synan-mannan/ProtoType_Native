# Native Poller Prototype for FS2

Minimal prototype replacing Java NIO with Linux epoll for Cats Effect fibers.

## Architecture

1. **NativePoller**: epoll_create1/ctl/wait via JNR FFI.
2. **EventLoop**: Background fiber polling events, dispatching to callbacks.
3. **PollingSystem**: Suspends fibers on untilReadable/Writable using Async[F].async, resumes on epoll event.
4. **Demo**: TCP echo server using non-blocking sockets + polling.

Fiber suspension: register FD interest, park until callback from loop resumes Deferred.

## Build & Run

Linux required for epoll.

```
sbt example/runMain com.example.nativepoller.example.EchoServer
```

Connect:

```
nc 127.0.0.1 8080
```

## Limitations

- Prototype: no error handling, edge-triggered not impl.
- JNR struct simplified.
- No macOS kqueue.
- Compile with sbt core/compile.

Diagram:

```
Fiber --register FD--> EpollPoller --epoll_wait--> EventLoop --callback--> resume Fiber
```
