# EchoServer Native TCP Implementation

## Progress

- [x] Created TODO.md with steps
- [x] Update EchoServer.scala
- [ ] Compile with sbt example/compile
- [ ] Run sbt example/run
- [ ] Test with nc localhost 8080

## Details

Implemented fully native Linux TCP echo server using:

- jnr-ffi LibC extension for socket/bind/listen/accept/read/write/close
- PollingSystem.untilReadable for accept/read
- Async[IO].blocking for syscalls (IO.blocking)
- Fiber spawn per client
- Manual sockaddr_in (127.0.0.1:8080)
- ~120 lines, no Java NIO
