---
title: Getting Started
description: A minimal, runnable example in 3 steps. Requires JDK 25+ and Maven.
---

A minimal, runnable example in 3 steps. Requires **JDK 25+** and **Maven**.

---

## Step 1: Add the Maven plugin and dependencies

```xml title="pom.xml"
<!-- Dependencies -->
<dependency>
    <groupId>com.pingidentity.opendst</groupId>
    <artifactId>opendst-api</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>

<!-- Plugin -->
<plugin>
    <groupId>com.pingidentity.opendst</groupId>
    <artifactId>opendst-maven-plugin</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <configuration>
        <parallelism>4</parallelism>
        <stagnationLimit>100</stagnationLimit>
    </configuration>
    <executions>
        <execution>
            <goals><goal>test</goal></goals>
        </execution>
    </executions>
</plugin>
```

`opendst-api` provides the Assert, Signals, and Simulator API. The simulation engine is injected automatically at runtime by the plugin.

---

## Step 2: Write a DST scenario

```java title="src/test/java/com/example/MyFirstDST.java"
import static com.pingidentity.opendst.api.Simulator.startNode;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;
import java.net.*;
import java.io.*;

public class MyFirstDST {

    public void run() throws IOException {
        startNode("server", "10.0.0.1", () -> server());
        startNode("client", "10.0.0.2", () -> client());
    }

    private Void server() throws Exception {
        var ss = new ServerSocket(8080);
        Signals.ready();              // Ready for fault injection
        var socket = ss.accept();
        var msg = new String(socket.getInputStream().readAllBytes());
        Assert.reachable("server-received", null);
        Assert.always(msg.length() > 0, "non-empty-message", null);
        return null;
    }

    private Void client() throws Exception {
        Signals.ready();
        var socket = new Socket("10.0.0.1", 8080);
        socket.getOutputStream().write("ping".getBytes());
        socket.close();
        return null;
    }
}
```

**No annotations needed.** DST classes are plain POJOs. The plugin discovers any class whose name ends with `DST` and runs every `public void` no-arg method.

`startNode(name, ip, callable)` spawns a simulated node with its own virtual IP. All socket and thread operations are intercepted deterministically.

`Signals.ready()` tells the simulator the node is initialized — fault injection begins after this call.

---

## Step 3: Run the simulation

```bash title="Terminal"
# Run all DST tests
$ mvn verify

# Run a specific test
$ mvn verify -Dopendst.test=com.example.MyFirstDST

# Run a specific method
$ mvn verify -Dopendst.test=com.example.MyFirstDST#run

# Replay a discovered failure (100% reproducible)
$ mvn verify -Dopendst.test=com.example.MyFirstDST \
    -Dopendst.plan=target/opendst/MyFirstDST/failures/failure-0.json
```

The plugin instruments your code, discovers tests, then runs simulations in parallel until the stagnation limit is reached (no new signals discovered). Failures are saved with their exact plan for instant replay.
