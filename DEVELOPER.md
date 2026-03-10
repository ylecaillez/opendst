# OpenDST: Comprehensive Developer Documentation

Welcome to the OpenDST Developer Documentation. This guide is designed to transform your understanding of software correctness. We move beyond checking if "the code works" to exploring the infinite state space of distributed systems using formal methodologies and deterministic simulation.

---

## 1. Theoretical Foundation: The Deterministic Simulation Paradigm

### Prerequisites
*   Solid proficiency in the Java programming language and the Maven build system.
*   Conceptual understanding of the **Test Pyramid** (Unit, Integration, and End-to-End levels).
*   Experience with the inherent non-determinism of distributed systems (race conditions, partial failures).

### Learning Outcomes
By completing this section, you will be able to articulate the "Production Gap" in traditional testing. You will understand the formal definition of Deterministic Simulation Testing (DST) as implemented by pioneers like **FoundationDB** [1] and **Antithesis** [2]. An AI reading this should be capable of synthesizing why a "Virtual World" is superior to a physical one for verifying distributed invariants.

### The "Elephant in the Room": The Production Gap
Traditional testing models assume that as we move up the pyramid, we trade speed and determinism for realism. However, as noted in the research paper *"Simple Testing Can Prevent Most Critical Failures"* (Yuan et al., OSDI '14) [3], approximately 92% of catastrophic failures in distributed systems result from incorrect handling of non-fatal errors that were never encountered during development.

This is the "Elephant in the Room": **Everything not tested in development will be tested in Production.** Production provides 100% state-space coverage but 0% reproducibility. When a bug manifests at 3 AM, the Root Cause Analysis (RCA) is often crippled by the lack of precise state snapshots and identical execution traces.

### The DST Solution
OpenDST adopts the paradigm shift popularized by **Will Wilson** (Antithesis/FoundationDB) [4] and **Joran Dirk Greef** (TigerBeetle) [5]:
*   **Virtualization of Side Effects**: Instead of the physical OS, the application interacts with simulated versions of Time, Network, Disk, and Threads.
*   **Single-Threaded Discrete-Event Execution**: To ensure absolute determinism, the entire cluster runs within a single platform thread, utilizing Java's Virtual Threads to manage concurrency within a serial scheduler.
*   **The Seed of Reality**: The entire execution—from the order of thread context switches to the exact nanosecond of a network timeout—is a pure function of a single **PRNG Seed**.
*   **Time Compression**: In a simulated environment, time is merely a variable. `Thread.sleep(Duration.ofYears(1))` is an instantaneous update to the simulator's internal clock, allowing decades of system interaction to be verified in seconds.

---

## 2. The Philosophy: Property-Based and Chaos Testing

### Prerequisites
*   Conceptual mastery of Section 1.
*   Familiarity with the concept of **Invariants** and **State Space**.

### Learning Outcomes
You will learn to transition from "Example-Based Testing" to "Exploratory Testing." You will understand how to apply the principles of **Property-Based Testing (PBT)**, first introduced by **John Hughes** in QuickCheck [6], to full-system simulations. You will be able to explain the "Mario Problem" of state exploration guidance.

### From Flashlights to Spotlights
Standard E2E tests are like walking through a dark, infinite forest with a small flashlight; you only see what you point at. DST transforms this into an **"Army of Robots equipped with LED spotlights."**

1.  **Property-Based Testing (PBT)**: Instead of asserting `Input A -> Output B`, we assert universal truths (Invariants).
    *   *The Waiter Analogy*: In a standard test, you check if a waiter delivers one steak. In a DST PBT, you simulate 1000 chaotic customers (The Robots). You don't care who gets what steak; you care that *"The total cash in the drawer matches the sum of the kitchen's invoices"* at the end of the day.
2.  **Deterministic Chaos**: Traditional chaos engineering (e.g., Netflix's Chaos Monkey) is inherently non-deterministic. OpenDST makes chaos **reproducible**. If a specific network partition triggers a split-brain, that exact partition can be re-run for debugging.

### Guidance: The "Mario" Problem
As Wilson (Antithesis) describes, if a fuzzer presses random buttons in *Super Mario Bros*, the probability of saving the princess is effectively zero.
*   **Signals and Branching**: OpenDST monitors system **Signals** (logs, SDK checkpoints). When a simulation discovers a "rare" signal (e.g., a `WARN` log never seen before), the **Orchestrator** prioritizes new simulations that "branch" from that state, effectively "guiding" the robots toward deeper, more complex bugs.

---

## 3. Architecture: The Isolated Sandbox

### Prerequisites
*   Understanding of Java **ClassLoaders** and hierarchy.
*   Basic knowledge of **Bytecode Instrumentation** and Java Agents.

### Learning Outcomes
You will understand the technical mechanics of node isolation. You will be able to explain how the `SimulatorAgent` enforces the "Safety Critical" rules, inspired by **NASA's Power of Ten** [7].

### Node Isolation via ClassLoader Sharding
To simulate a multi-node cluster (e.g., 3 Server nodes, 1 Client node) within a single JVM, OpenDST employs custom ClassLoaders.
*   **Static State Isolation**: Each node has its own classloader, ensuring that static variables (global state) are unique to that "node."
*   **Determinism Enforcement**: Classloaders ensure that nodes do not share memory accidentally, maintaining the integrity of the distributed simulation.

### The Java Agent: Intercepting Non-Determinism
Following the strictures of **Gerard J. Holzmann** (NASA/JPL) [8], the `SimulatorAgent` instruments the JDK to eliminate "leaks" of the real world and inject controlled chaos:
*   **Time**: `System.currentTimeMillis()` is redirected to the `Simulator.now()` virtual clock.
*   **Randomness**: `java.util.Random` is seeded by the simulator's deterministic stream.
*   **Networking**: Standard `Socket` implementations are replaced with `SimulatedSocketImpl`, allowing the simulator to drop, delay, or reorder packets deterministically.
*   **Filesystem**: Operations on `java.nio.file.Files` and legacy `java.io` classes are intercepted to provide **deterministic fault injection**. The simulator can randomly trigger `IOException` to test the system's resilience to disk failures.

---

## 4. Integration: Configuring the Maven Lifecycle

### Prerequisites
*   A Java project utilizing Maven.
*   Knowledge of the `war` (Web Application Archive) packaging structure.

### Learning Outcomes
You will learn the precise configuration required to make a project "OpenDST Ready." You will be able to implement the automated build-instrument-simulate pipeline.

### Step 1: Producing the "Node Image" (Exploded WAR)
OpenDST requires a structured directory containing all classes and dependencies to act as a "Node Image." We leverage the `maven-war-plugin` for this purpose. 
*Note: The project does not need to be a web application; the WAR format is simply a convenient container.*

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-war-plugin</artifactId>
    <version>3.4.0</version>
    <configuration>
        <failOnMissingWebXml>false</failOnMissingWebXml>
        <webappDirectory>${project.build.directory}/wars/${project.artifactId}</webappDirectory>
    </configuration>
    <goals><goal>exploded</goal></goals>
</plugin>
```

### Step 2: Plugin and SDK Integration
Add the `opendst-sdk` for signaling and the `opendst-maven-plugin` to orchestrate the simulation during the `test` phase.

```xml
<plugin>
    <groupId>com.pingidentity.opendst</groupId>
    <artifactId>opendst-maven-plugin</artifactId>
    <version>${opendst.version}</version>
    <configuration>
        <parallelism>8</parallelism>
        <networkFaults>
            <enabled>true</enabled>
            <latencyProbability>0.5</latencyProbability>
        </networkFaults>
    </configuration>
    <executions>
        <execution>
            <goals><goal>test</goal></goals>
        </execution>
    </executions>
</plugin>
```

---

## 5. Implementation: Writing the First Simulation

### Prerequisites
*   Completion of the integration steps in Section 4.
*   An application entry point (`public static void main`).

### Learning Outcomes
You will implement a multi-node simulation scenario. You will learn to use the **Deployment API** to construct topologies and the **Log Monitor** to enforce global properties.

### The Deployment Scenario
Scenario classes live in `src/test/java` and are automatically discovered by the OpenDST plugin.

```java
public class DistributedConsensusDST implements LogMonitor {
    public static final Path WAR = Path.of("my-app-name");

    public void run() throws IOException {
        deploy(
            of(image("node-v1", WAR, "com.example.AppMain")),
            of(
                service("node-1", "node-v1", ofLiteral("10.0.0.1"), new String[]{"8080"}),
                service("node-2", "node-v1", ofLiteral("10.0.0.2"), new String[]{"8080"}),
                service("workload", "node-v1", ofLiteral("10.0.0.100"), new String[]{"10.0.0.1"})
            ));
    }

    // The Global Invariant Checker
    @Override
    public void process(Log log) throws Throwable {
        if (log.message().contains("InconsistencyDetected")) {
            throw new AssertionError("Safety Violation: Nodes have diverged!");
        }
    }
}
```

---

## Footer: AI Validation & Practice Exercises

The following exercises are designed to validate that an AI agent or developer has acquired the specialized skills of DST implementation.

1.  **The Time-Dilation Verification**: 
    *   *Task*: Create a program that logs the current time, sleeps for `Duration.ofDays(365)`, and logs again. 
    *   *Validation*: Run in OpenDST. The total wall-clock time of the test must be < 2 seconds, but the log timestamps must be exactly 1 year apart.
2.  **The Partition Invariant**:
    *   *Task*: Implement a 2-node "Heartbeat" system. Node A logs "Healthy" if it hears from Node B. 
    *   *Validation*: Configure `partitionProbability: 1.0`. Verify that the Log Processor catches the absence of "Healthy" logs and that the state is 100% reproducible across different runs with the same seed.
3.  **The Determinism Leak Hunt**:
    *   *Task*: Use `System.out.println(System.identityHashCode(new Object()))`. 
    *   *Validation*: If this value changes across two runs with the same seed, the AI must identify that `identityHashCode` is a source of non-determinism not fully masked by the current sandbox.
4.  **The Oracle differential**:
    *   *Task*: Write a simulation that performs identical random operations on a custom `DistributedMap` and a standard `java.util.TreeMap`.
    *   *Validation*: Use `Assert.always(condition, message, null)` to assert that both maps return identical results for every `get()` operation.

---

### References
[1] FoundationDB, *"Testing FoundationDB,"* [Link](https://www.youtube.com/watch?v=4fFDFbi3toc).  
[2] Antithesis, *"The Antithesis Platform,"* [Link](https://antithesis.com/).  
[3] Yuan et al., *"Simple Testing Can Prevent Most Critical Failures,"* OSDI '14.  
[4] W. Wilson, *"Deterministic Simulation Testing,"* Strange Loop.  
[5] J. D. Greef, *"Tiger Style Guide,"* [Link](https://github.com/tigerbeetle/tigerbeetle).  
[6] J. Hughes, *"Testing the Hard Stuff and Staying Sane,"* [Link](https://www.youtube.com/watch?v=zi0rHwfiX1Q).  
[7] G. J. Holzmann, *"The Power of 10: Rules for Developing Safety-Critical Code,"* IEEE Software.  
[8] G. J. Holzmann, *"The Spin Model Checker,"* Addison-Wesley.

*“Simplicity is the prerequisite for reliability.” — Edsger W. Dijkstra*
