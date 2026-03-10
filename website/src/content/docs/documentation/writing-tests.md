---
title: Writing DST Scenarios
description: Describe your distributed system topology in deployment.yaml and write plain Java applications.
---

OpenDST scenarios are defined declaratively. You write plain Java applications with `public static void main(String[])` entry points, then describe the deployment topology in a `deployment.yaml` file. No test framework, no annotations, no base classes.

---

## Project Layout

Application classes live in `src/main/java`. The deployment topology is described in `deployment.yaml` at the project root. There are no "test classes" — the simulator runs your real application code.

```
my-project/
├── deployment.yaml              # Topology descriptor
├── pom.xml                      # Maven config with opendst-maven-plugin
└── src/main/java/
    └── com/example/
        ├── Server.java          # Service with main()
        ├── Client.java          # Service with main()
        └── MyTraceAuditor.java  # Optional: log observer
```

---

## The Deployment Descriptor

`deployment.yaml` defines the services that make up your distributed system. Each service maps to a Java class with a `public static void main(String[])` method.

```yaml title="deployment.yaml"
services:
  server:
    class: com.example.Server
    ip: 10.0.0.1
    args: ["8080"]
  client:
    class: com.example.Client
    ip: 10.0.0.2
    args: ["10.0.0.1", "8080"]

traceAuditor:
  class: com.example.MyTraceAuditor
```

### Service Properties

| Property | Required | Description |
|----------|----------|-------------|
| `class`  | yes      | Fully-qualified class name with `public static void main(String[])`. Use `$` for inner classes (e.g., `MyApp$Server`). |
| `ip`     | yes      | Virtual IP address for this node. |
| `args`   | no       | Command-line arguments passed to `main()`. |
| `dir`    | no       | Local directory path containing the application classes. Defaults to the current project's `target/classes/` and runtime dependencies. |
| `artifact` | no     | Maven GAV coordinate (e.g., `com.example:my-app:war:1.0.0`). Used when the service comes from a different Maven module. |

### Source Resolution

Each service resolves its classpath from one of three sources (mutually exclusive):

1. **Current project** (default) — when neither `dir` nor `artifact` is set, the service uses the current project's compiled classes and runtime dependencies.
2. **Local directory** (`dir`) — a path to an exploded WAR or classes directory. The simulator loads classes from `WEB-INF/classes/` and `WEB-INF/lib/`.
3. **Maven artifact** (`artifact`) — a Maven GAV coordinate resolved from local/remote repositories.

---

## Writing Services

Each service is a standalone Java class with a `main` method. Use the `opendst-sdk` for assertions and lifecycle signals:

```java title="src/main/java/com/example/EchoApp.java"
import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;
import java.io.*;
import java.net.*;

public class EchoApp {

    public static class Server {
        public static void main(String[] args) throws Exception {
            var port = Integer.parseInt(args[0]);
            try (var ss = new ServerSocket(port);
                 var socket = ss.accept();
                 var in = new DataInputStream(socket.getInputStream());
                 var out = new DataOutputStream(socket.getOutputStream())) {
                Signals.ready();              // Ready for fault injection
                int value = in.readInt();
                Assert.reachable("server-received", null);
                out.writeInt(value + 1);
            }
        }
    }

    public static class Client {
        public static void main(String[] args) throws Exception {
            var host = args[0];
            var port = Integer.parseInt(args[1]);
            Signals.ready();
            try (var socket = new Socket(host, port);
                 var out = new DataOutputStream(socket.getOutputStream());
                 var in = new DataInputStream(socket.getInputStream())) {
                out.writeInt(42);
                int response = in.readInt();
                Assert.always(response == 43, "echo-correct", null);
            }
        }
    }
}
```

Each service runs in its own classloader-isolated node with a virtual IP. All socket, thread, and time operations are intercepted deterministically.

---

## Trace Auditing

Implement the `TraceAuditor` interface to observe log output from all simulated nodes in real time. The trace auditor runs **outside** the simulation context to protect determinism.

```java title="src/main/java/com/example/MyTraceAuditor.java"
import com.pingidentity.opendst.api.TraceAuditor;

public class MyTraceAuditor implements TraceAuditor {
    @Override
    public void process(Log log) throws Throwable {
        if (log.message().contains("ERROR")) {
            throw new AssertionError("Unexpected error: " + log.message());
        }
    }
}
```

Reference it in `deployment.yaml`:

```yaml
traceAuditor:
  class: com.example.MyTraceAuditor
```

The `Log` record contains: `host` (node name), `time` (simulated instant), `iteration` (PRNG step count), and `message` (the log line).

:::caution
`TraceAuditor.process()` runs outside the simulation. Throwing an exception stops the simulation and marks it as a failure. Do not share mutable state with service code.
:::

---

## Multi-Module Projects

For projects where the application under test lives in a separate Maven module, use the `artifact` property to reference it by GAV coordinate, or `dir` to point to its exploded WAR directory:

```yaml title="deployment.yaml"
services:
  alice:
    class: com.example.bank.AccountServer
    artifact: com.example:bank-app:war:1.0.0
    ip: 10.0.0.1
    args: ["8001", "1000"]
  bob:
    class: com.example.bank.AccountServer
    artifact: com.example:bank-app:war:1.0.0
    ip: 10.0.0.2
    args: ["8002", "1000"]
  coordinator:
    class: com.example.bank.Coordinator
    artifact: com.example:bank-app:war:1.0.0
    ip: 10.0.0.3
    args: ["10.0.0.1", "8001", "10.0.0.2", "8002", "20"]
```

When using `artifact`, the plugin resolves the WAR from Maven repositories, extracts it, instruments the bytecode, and packages everything into the self-contained simulation JAR.
