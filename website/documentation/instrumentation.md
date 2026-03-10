---
title: Instrumentation
description: How OpenDST replaces all sources of non-determinism with controlled, simulated alternatives.
---

# Instrumentation

OpenDST uses a **hybrid instrumentation** approach to replace all sources of non-determinism with controlled, simulated alternatives.

---

## Two Tiers

### Offline (Maven Plugin)

Application and test classes are transformed **before** any simulation runs, using the JDK [ClassFile API](https://openjdk.org/jeps/457) (JEP 457).

**Call-site replacement:** Patterns like `new Socket()` or `Files.read()` are rewritten to call the simulator's deterministic factory methods instead.

### Runtime (Java Agent)

JDK internal classes cannot be transformed offline. The `opendst-agent.jar` agent uses **ByteBuddy advice** to intercept JDK methods at class-load time.

This covers things like `System.currentTimeMillis()`, `Random.next()`, and virtual thread construction that happen deep inside the JDK.

---

## What Gets Intercepted

| Category | JDK APIs Intercepted | Replaced With |
|----------|---------------------|---------------|
| **Time** | `System.currentTimeMillis()`, `System.nanoTime()`, `Clock.currentInstant()` | Virtual time from the discrete-event scheduler. Only advances when a task runs. |
| **Randomness** | `Random`, `ThreadLocalRandom`, `SplittableRandom`, `SecureRandom`, collection salts | Single deterministic PRNG controlled by the plan's seed segments. Iteration counter drives segment switching. |
| **Threads** | `new Thread()`, `Thread.setDaemon()`, `Executors.defaultThreadFactory()`, virtual thread internals | All threads become virtual threads attached to the current node. Deterministic thread IDs starting at 10,000. |
| **Networking** | `new Socket()`, `new ServerSocket()`, `InetAddress.getLocalHost()`, DNS (SPI), socket/server factories | In-memory TCP using ring buffers. Per-node IP/DNS. Deterministic connect/accept handshake via synchronous queues. |
| **Filesystem** | All `Files.*` methods, `new FileInputStream()`, `new FileOutputStream()`, `new RandomAccessFile()` | Real filesystem operations with a fault injection hook before each call. |
| **System** | `Runtime.exit()`, `Runtime.addShutdownHook()`, `System.setOut()`/`setErr()` | Per-node shutdown hooks. `exit()` throws `SystemExitError`. Console redirection is blocked. |
| **GC** | `ReferenceQueue.poll()`, `ReferenceQueue.remove()` | Neutralized (always null / block). Prevents GC-dependent non-determinism. |
| **SDK** | `Assert.*`, `Signals.*` | Redirected from empty SDK stubs to `AssertImpl` / `SignalsImpl` in the simulator. |

---

## JVM Options

The runner automatically adds these JVM options to child simulation processes:

```
--enable-native-access=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
-XX:+UnlockExperimentalVMOptions
-XX:hashCode=2    // deterministic identityHashCode
```
