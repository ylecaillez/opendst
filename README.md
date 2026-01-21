<!--
 ! Copyright 2024-2025 Ping Identity Corporation
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

OpenDST uses a Java Agent (`SimulatorAgent`) to instrument bytecode at runtime. It replaces calls to non-deterministic APIs with calls to the `Simulator`.

When running inside a simulation:
*   **Time:** `System.currentTimeMillis()` and `System.nanoTime()` return a simulated time that advances only when the simulator decides.
*   **Threads:** `new Thread()` and `startVirtualThread()` are intercepted to run as virtual threads managed by the simulator's scheduler.
*   **Randomness:** `ThreadLocalRandom`, `SecureRandom`, and `Random` are seeded deterministically.
*   **Network:** (Planned/In-progress) Network interactions can be simulated to test partition tolerance and message ordering.

## Usage

### Prerequisites

*   Java 25 or later (Preview features enabled).
*   Maven.

### Running Tests

Because OpenDST relies on a Java Agent to instrument the JDK, you must run your tests with the agent attached. The project is configured to build the agent jar and attach it automatically during the `integration-test` phase.

To run the tests:

```bash
mvn clean install
```

### Writing a Deterministic Test

Wrap your test logic in `Simulator.runSimulation()`:

```java
import static com.pingidentity.opendst.Simulator.runSimulation;
import static java.lang.System.currentTimeMillis;
import static java.lang.Thread.sleep;
import static java.time.Duration.ofHours;
import static org.assertj.core.api.Assertions.assertThat;

public class MyDeterministicTest {

    @Test
    public void testTimeTravel() {
        runSimulation(() -> {
            long start = currentTimeMillis();
            
            // This sleeps for 1 hour in simulation time, but returns instantly in real time
            sleep(ofHours(1).toMillis());
            
            long end = currentTimeMillis();
            assertThat(end - start).isEqualTo(ofHours(1).toMillis());
            return null;
        });
    }
}
```

## Architecture

*   **`Simulator`:** The core engine that manages the simulation loop, virtual time, and task scheduling.
*   **`SimulatorAgent`:** A Java Agent that uses ByteBuddy to intercept JDK methods and redirect them to the `Simulator`.
*   **`Machine`:** Represents a node in the distributed system simulation (context for the current execution).

## References & Further Reading

To learn more about Deterministic Simulation Testing and why it is a game-changer for distributed systems reliability, check out these resources:

*   **[Testing Distributed Systems with Deterministic Simulation](https://www.youtube.com/watch?v=4fFDFbi3toc)** (Will Wilson, FoundationDB): The seminal talk that popularized the technique.
*   **[FoundationDB Testing](https://apple.github.io/foundationdb/testing.html):** Detailed documentation on how FoundationDB uses simulation.
*   **[TigerBeetle: Simulation](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/TIGER_STYLE.md#simulation):** How TigerBeetle uses deterministic simulation to ensure correctness.
*   **[Rewriting the heart of our sync engine](https://dropbox.tech/infrastructure/-testing-our-new-sync-engine):** How Dropbox used deterministic simulation to rewrite their synchronization engine.
*   **[Antithesis: Determinism](https://antithesis.com/docs/determinism/):** A deep dive into determinism and its role in testing.

## License

This project is licensed under the Apache License, Version 2.0.
