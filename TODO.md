# Fix Native Poller Compilation Errors

**Status:** Progressing through fixes for Cats Effect spawn imports and JNR FFI type mismatches.

## Steps:

- [x] 1. Create TODO.md
- [ ] 2. Edit EventLoop.scala (spawn import + addCallback discard warning fix)
- [ ] 3. Edit NativePoller.scala (JNR interop: int32_t casts, Pointer.NULL, runtime.alloc, unregister param, makeLinux call)
- [ ] 4. Edit PollingSystem.scala (spawn import + await cb type fix)
- [ ] 5. Test compilation: cd native-poller && sbt clean compile
- [ ] 6. Test run: cd native-poller && sbt "example/runMain com.example.nativepoller.example.EchoServer"
- [ ] 7. Complete task
