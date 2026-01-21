---
title: Writing DST Scenarios
description: DST tests are plain Java classes. No annotations, no base classes, no test framework.
---

DST tests are plain Java classes. No annotations, no base classes, no test framework.

---

## Structural Rules

- Class name must end with `DST` (or match your custom `includes` pattern).
- Must have a **public no-arg constructor**.
- Each test method must be `public void` with zero parameters (can throw exceptions).
- Place test files in `src/test/java` as usual.
- Just write your simulation logic directly in the method body — the framework wraps it automatically.

---

## Pattern A: Single-Node

The simplest pattern. The entire scenario runs inline. Good for testing algorithms, data structures, or single-process logic under randomized inputs.

```java title="SingleNodeDST.java"
public class SingleNodeDST {
    public void run() {
        Signals.ready();
        int value = ThreadLocalRandom.current().nextInt(100);
        Assert.always(value >= 0, "non-negative", null);
        Assert.sometimes(value > 50, "sometimes-large", null);
    }
}
```

---

## Pattern B: Multi-Node with `startNode()`

For distributed scenarios. Each node gets its own hostname, IP address, and console. Nodes communicate over simulated TCP sockets. All standard Java networking APIs (`Socket`, `ServerSocket`) work transparently.

```java title="MultiNodeDST.java"
public class MultiNodeDST {
    public void run() throws IOException {
        startNode("server", "10.0.0.1", () -> server());
        startNode("client", "10.0.0.2", () -> client());
    }

    private Void server() throws Exception {
        var ss = new ServerSocket(8080);
        Signals.ready();
        // ... accept connections, process requests
        return null;
    }

    private Void client() throws Exception {
        Signals.ready();
        var socket = new Socket("10.0.0.1", 8080);
        // ... send requests, verify responses
        return null;
    }
}
```

:::note
Node methods return `Void` (capital V) because they are `Callable<Void>`. Always return `null` at the end.
:::

---

## Pattern C: Deployment API (Full Isolation)

For testing real distributed applications. Each service gets its own `ClassLoader`, working directory, and filesystem. Think of `image()` as a Docker image and `service()` as a container.

```java title="DeploymentDST.java"
import static com.pingidentity.opendst.Deployment.Image.image;
import static com.pingidentity.opendst.Deployment.Service.service;
import static com.pingidentity.opendst.Simulator.deploy;

public class DeploymentDST {
    static final Path WAR = Path.of("my-app");

    public void run() throws IOException {
        deploy(
            of(image("server-img", WAR, "com.example.Server")),
            of(service("node-1", "server-img", ofLiteral("10.0.0.1"), args("8080")),
               service("node-2", "server-img", ofLiteral("10.0.0.2"), args("8080"))));
    }
}
```

**Images** define a reusable blueprint: a WAR directory path, a main class, and an optional extra classpath. The WAR directory is resolved relative to `target/opendst/instrumented-wars/`.

**Services** are instances of an image, each with a unique hostname, IP, and optional `main(String[] args)` arguments.

**Filesystem isolation:** Files under `WEB-INF/fs/` in the image are copied to the service's working directory at startup.

---

## Log Monitoring

Implement the `LogMonitor` interface on your DST class to observe log output from all simulated nodes in real time. The framework detects the interface automatically.

```java title="MonitoredDST.java"
public class MonitoredDST implements LogMonitor {
    public void run() throws IOException {
        startNode("server", "10.0.0.1", this::server);
        startNode("client", "10.0.0.2", this::client);
    }

    @Override
    public void process(Log log) throws Throwable {
        if (log.message().contains("ERROR")) {
            throw new AssertionError("Unexpected error: " + log.message());
        }
    }
}
```

:::caution
`LogMonitor.process()` runs **outside** the simulation context to protect determinism. Do not share mutable state between your `run()` method and your `process()` method.
:::
