---
title: Getting Started
description: A minimal, runnable example in 3 steps. Requires JDK 25+ and Maven.
---

A minimal, runnable example in 3 steps. Requires **JDK 25+** and **Maven**.

---

## Step 1: Add the Maven plugin and dependencies

```xml
<!-- Dependencies -->
<dependency>
    <groupId>com.pingidentity.opendst</groupId>
    <artifactId>opendst-sdk</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>

<!-- Plugin -->
<plugin>
    <groupId>com.pingidentity.opendst</groupId>
    <artifactId>opendst-maven-plugin</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <executions>
        <execution>
            <goals><goal>build</goal></goals>
            <configuration>
                <descriptor>${project.basedir}/deployment.yaml</descriptor>
                <parallelism>4</parallelism>
                <stagnationLimit>100</stagnationLimit>
            </configuration>
        </execution>
    </executions>
</plugin>
```

`opendst-sdk` provides the Assert, Signals, and TraceAuditor API. The SDK methods are empty stubs at compile time; the plugin rewrites all call-sites to the actual simulation implementation during the build.

---

## Step 2: Write your application

Each service is a class with a `public static void main(String[])` method. Use `opendst-sdk` for assertions and lifecycle signals:

```java
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

Then describe the deployment topology in `deployment.yaml`:

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

Each service runs in its own classloader-isolated node with a virtual IP. All socket, thread, and time operations are intercepted deterministically.

`Signals.ready()` tells the simulator the node is initialized — fault injection begins after this call.

---

## Step 3: Build and run the simulation

```bash
# Build the self-contained simulation JAR
$ mvn package

# Run the simulation
$ java -jar target/<your-project>-<version>-opendst.jar
```

The plugin instruments your bytecode, discovers assertions, and packages everything — including the orchestrator, the simulator agent, and your applications with their dependencies — into a self-contained JAR. When executed, the JAR's runner explores different execution schedules in parallel until the stagnation limit is reached (no new signals discovered). Failures are saved with their exact plan for instant replay.
