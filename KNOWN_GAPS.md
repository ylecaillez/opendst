# Known Gaps & Non-Deterministic Leakages

This document lists JDK APIs and system features that are currently not isolated or fully instrumented in OpenDST 0.1. Tests relying on these features may exhibit non-deterministic behavior or leak state between simulated nodes.

## System & Environment
- **System Properties**: `System.getProperty()` and `System.getProperties()` are shared across all nodes and reflect the host JVM environment.
- **Environment Variables**: `System.getenv()` is not isolated per node.
- **Process Management**: `ProcessBuilder` and `Runtime.exec()` spawn real OS processes that are not controlled by the simulator's scheduler or network stack.

## File System
- **File I/O**: `java.io.File`, `java.io.FileInputStream/OutputStream`, and `java.nio.file` are not virtualized. 
- **Non-determinism**: File timestamps (`lastModified`), file existence checks, and directory listing order are non-deterministic and reflect the host file system.
- **Isolation**: Nodes share the same file system. Concurrent access to the same paths will lead to race conditions and non-determinism.

## Low-Level & Native
- **Direct Memory**: `sun.misc.Unsafe` and `java.lang.foreign` (Panama) are not intercepted.
- **JNI**: Native libraries loaded via `System.loadLibrary()` can perform arbitrary non-deterministic operations and bypass all simulation controls.
- **Signal Handling**: OS signals (`sun.misc.Signal`) are not intercepted.

## Networking
- **Non-blocking I/O (NIO)**: `java.nio.channels` (e.g., `SocketChannel`, `ServerSocketChannel`, `Selector`) is not yet simulated. Only blocking `java.net.Socket` and `ServerSocket` are supported.
- **Datagrams (UDP)**: `java.net.DatagramSocket` and `MulticastSocket` are not yet simulated.
- **Advanced Features**: IPv6-specific features, socket options beyond standard TCP ones, and low-level traffic control are not supported.

## Miscellaneous
- **Class Loading**: While nodes use isolated class loaders for application classes, system classes are shared. Static state in shared system classes can lead to state leakage between nodes.
- **Hardware Info**: `Runtime.availableProcessors()`, `Runtime.freeMemory()`, etc., reflect the host machine.
