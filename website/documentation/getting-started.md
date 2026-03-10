---
title: Quick Start
description: A minimal echo server/client in 3 steps. Requires JDK 25+ and Maven.
---

# Quick Start

A minimal echo server/client in 3 steps. Requires **JDK 25+** and **Maven**.

For a guided walkthrough that explains the concepts, see the [Tutorial](/why-dst).

---

## 1. Add the plugin and SDK

```xml
<dependency>
    <groupId>com.pingidentity.opendst</groupId>
    <artifactId>opendst-sdk</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>

<plugin>
    <groupId>com.pingidentity.opendst</groupId>
    <artifactId>opendst-maven-plugin</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <executions>
        <execution>
            <goals><goal>build</goal></goals>
            <configuration>
                <descriptor>${project.basedir}/deployment.yaml</descriptor>
            </configuration>
        </execution>
    </executions>
</plugin>
```

The `build` goal is bound to the `package` phase. It instruments your bytecode, discovers assertions, and packages everything into a self-contained `-opendst.jar`.

## 2. Write your application

Each service is a class with a `public static void main(String[])` entry point:

```java
import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;
import java.io.*;
import java.net.*;

public class EchoApp {

    public static class Server {
        public static void main(String[] args) throws Exception {
            var port = Integer.parseInt(args[0]);
            try (var serverSocket = new ServerSocket(port);
                 var socket = serverSocket.accept();
                 var in = new DataInputStream(socket.getInputStream());
                 var out = new DataOutputStream(socket.getOutputStream())) {
                Signals.ready();
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

Describe the deployment topology in `deployment.yaml`:

```yaml
services:
  server:
    class: com.example.EchoApp$Server
    ip: 10.0.0.1
    args: ["8080"]
  client:
    class: com.example.EchoApp$Client
    ip: 10.0.0.2
    args: ["10.0.0.1", "8080"]
```

## 3. Build and run

```bash
mvn package
java -jar target/<your-project>-<version>-opendst.jar
```

The JAR contains everything — your application, the orchestrator, the simulator agent, and all dependencies. When executed, it explores different execution schedules and fault scenarios until the stagnation limit is reached (no new signals discovered). Failures are saved with their exact plan for deterministic replay.

---

## Key concepts

| Concept | Purpose |
|---|---|
| `Signals.ready()` | Required for fault injection. Without it, no faults are injected. Call it after initialization. |
| `Assert.always(cond, name, details)` | Invariant — must be true on every execution path. |
| `Assert.reachable(name, details)` | Liveness — this code must be reached at least once across all runs. |
| `deployment.yaml` | Declares the services, their virtual IPs, and startup arguments. |
| `-opendst.jar` | Self-contained executable. Run with `java -jar`. |

## What's next

- [Architecture](/documentation/architecture) — How the orchestrator, simulator, and agent fit together
- [Assertions](/documentation/assertions) — Full assertion API reference
- [Configuration](/documentation/configuration) — Build parameters and runtime defaults
- [Fault Injection](/documentation/faults) — What faults are injected and how
