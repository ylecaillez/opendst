<!--
 ! Copyright 2024-2026 Ping Identity Corporation
 !
 ! Licensed under the Apache License, Version 2.0 (the "License");
 ! you may not use this file except in compliance with the License.
 ! You may obtain a copy of the License at
 !
 !    http://www.apache.org/licenses/LICENSE-2.0
 !
 ! Unless required by applicable law or agreed to in writing, software
 ! distributed under the License is distributed on an "AS IS" BASIS,
 ! WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ! See the License for the specific language governing permissions and
 ! limitations under the License.
 -->
# OpenDST: Deterministic Simulation Testing

OpenDST is a Java library designed to enable **Deterministic Simulation Testing (DST)** for distributed systems. It allows developers to test complex, concurrent, and distributed logic in a completely deterministic environment, making flaky tests a thing of the past.

By intercepting non-deterministic operations (like time, threading, and randomness) and replacing them with a controlled simulation, OpenDST ensures that every test run produces the exact same result for a given seed.

## Key Features

*   **Deterministic Execution:** Eliminates flaky tests by controlling all sources of non-determinism.
*   **Virtual Time:** Simulates time progression instantly. `Thread.sleep(Duration.ofHours(1))` completes in milliseconds.
*   **Deterministic Scheduling:** Uses a custom scheduler (leveraging Java Virtual Threads) to control thread execution order.
*   **Controlled Randomness:** Provides a deterministic source of randomness that can be seeded for reproducibility.
*   **Bytecode Instrumentation:** Automatically intercepts JDK calls (e.g., `System.currentTimeMillis()`, `new Thread()`, `SecureRandom`) using a Java Agent, so you don't need to change your production code.

## How It Works

OpenDST uses two instrumentation strategies. Application code is instrumented **offline** by the Maven plugin using the JDK 25 ClassFile API (JEP 484), which rewrites SDK stub call-sites at build time. JDK internals are handled at **runtime** by a lightweight Java Agent (`SimulatorAgent`) that intercepts non-deterministic APIs and redirects them to the Simulator.

When running inside a simulation:
*   **Time:** `System.currentTimeMillis()` and `System.nanoTime()` return a simulated time that advances only when the simulator decides.
*   **Threads:** `new Thread()` and `startVirtualThread()` are intercepted to run as virtual threads managed by the simulator's scheduler.
*   **Randomness:** `ThreadLocalRandom`, `SecureRandom`, and `Random` are seeded deterministically.
*   **Network:** Network interactions are simulated with a virtual IP stack, supporting programmable latency, partitions, and connection resets.

## Determinism & Isolation

OpenDST aims for strict determinism, but some JDK APIs are not yet isolated or fully instrumented. For an exhaustive list of known gaps (including File I/O and certain system properties), see [KNOWN_GAPS.md](KNOWN_GAPS.md).

## Usage

### Prerequisites

*   Java 25 or later.
*   Maven.

### Running Simulations

The Maven plugin's `build` goal (bound to the `package` phase) instruments your code offline and packages everything into a self-contained `-opendst.jar`. To run a simulation, execute the JAR directly:

```bash
mvn clean package
java -jar target/*-opendst.jar
```

### Writing a Deterministic Test

Each service is a class with a `public static void main(String[])` entry point. Use the `opendst-sdk` for assertions and lifecycle signals:

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

Describe the deployment topology in a `deployment.yaml`:

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

Configure the Maven plugin with the `build` goal:

```xml
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

Run the simulation from the produced JAR. All orchestration parameters are CLI arguments:

```bash
java -jar target/*-opendst.jar \
  --stagnation-limit 100 \
  --duration 100000 \
  --branch-probability 0.7 \
  --replay-probability 0.05 \
  --working-dir target/opendst-work
```

Available CLI options:

| Option | Default | Description |
|--------|---------|-------------|
| `--duration` | 100000 | Maximum simulation duration in milliseconds |
| `--stagnation-limit` | 100 | Stop after N iterations without new coverage |
| `--branch-probability` | 0.7 | Probability of branching to explore a new path |
| `--replay-probability` | 0.05 | Probability of replaying a previous trace |
| `--forkCount` | max(1, CPUs - 1) | Number of concurrent simulation forks |
| `--working-dir` | (JAR name sans `.jar`) | Persistent working directory for deployment, runs, and reports |
| `--fail-fast` | false | Stop on first assertion failure |
| `--jvm-args` | (from POM) | JVM arguments for child processes |

The working directory has the following structure:

```
myapp-opendst/              # --working-dir (default: JAR path minus .jar)
  deployment/               # extracted from JAR (skipped if already present)
  runs/                     # ephemeral per-fork directories
  report/                   # simulation output (persists across runs)
    report.json
    plans/                  # execution plans and simulator logs
```

## Architecture

*   **`Simulator`:** The core engine that manages the simulation loop, virtual time, and task scheduling.
*   **`SimulatorAgent`:** A Java Agent that uses the ClassFile API (JEP 484) to intercept JDK methods and redirect them to the `Simulator`.
*   **`Node`:** Represents a node in the distributed system simulation (context for the current execution).

## References & Further Reading

To learn more about Deterministic Simulation Testing and why it is a game-changer for distributed systems reliability, check out these resources:

*   **[Testing Distributed Systems with Deterministic Simulation](https://www.youtube.com/watch?v=4fFDFbi3toc)** (Will Wilson, FoundationDB): The seminal talk that popularized the technique.
*   **[FoundationDB Testing](https://apple.github.io/foundationdb/testing.html):** Detailed documentation on how FoundationDB uses simulation.
*   **[TigerBeetle: Simulation](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/TIGER_STYLE.md#simulation):** How TigerBeetle uses deterministic simulation to ensure correctness.
*   **[Rewriting the heart of our sync engine](https://dropbox.tech/infrastructure/-testing-our-new-sync-engine):** How Dropbox used deterministic simulation to rewrite their synchronization engine.
*   **[Antithesis: Determinism](https://antithesis.com/docs/determinism/):** A deep dive into determinism and its role in testing.

## License

This project is licensed under the Apache License, Version 2.0.
