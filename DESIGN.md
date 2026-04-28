# OpenDST

A deterministic simulation testing framework.

## What is an OpenDST test?

OpenDST explores the state-space of a system under test (SUT) to find bugs. It relies on a
pseudo-random number generator to create test scenarios at runtime. A test is made of three things:

* **Invariants** -- properties the system must maintain in every situation. OpenDST will try to
  defeat them.
* **Test workload** -- creates traffic/actions against the SUT (e.g. HTTP requests).
* **Fault injection** -- conditions injected by OpenDST to break invariants (e.g. network
  partitions, thread interleaving).

## Building an OpenDST test

A test is made of one or more applications (the SUT) and one or more test workloads. The
`opendst-maven-plugin` packages everything into a single self-contained JAR:

```shell
java -jar simulation-opendst.jar
```

The JAR is self-sufficient -- it contains the SUT, all its dependencies, and the entire OpenDST
machinery needed to run the simulation.

### Deployment descriptor

A `deployment.yaml` defines the services to simulate:

```yaml
services:
  server:
    class: com.example.App$Server
    ip: 10.0.0.1
    args: ["8080"]
  client:
    class: com.example.App$Client
    ip: 10.0.0.2
```

### Maven configuration

```xml
<plugin>
  <groupId>com.pingidentity.opendst</groupId>
  <artifactId>opendst-maven-plugin</artifactId>
  <version>${latest.version}</version>
  <configuration>
    <descriptor>${project.basedir}/deployment.yaml</descriptor>
  </configuration>
</plugin>
```

This produces `appname-opendst.jar`.

## Execution architecture

### Self-contained JAR structure

The Maven plugin produces a JAR with this layout:

```
simulation-opendst.jar
  Bootstrap.class                     # Main-Class entry point (no dependencies)
  META-INF/opendst/
    assertions.json                   # Discovered properties (Set<Assertion>)
    build-config.json                 # Baked CLI/JVM settings from the Maven build
    deployment.json                   # Runtime deployment descriptor (post-enrichment)
  system/
    opendst-agent.jar                 # Shaded Java agent + child entry point
                                      #   (ByteBuddy, jackson-jr, SimulationLauncher)
    opendst-runner.jar                # Runner (parent only): Bootstrap, RunnerCli,
                                      #   Planner, ReportGenerator (jackson-jr, picocli)
    opendst-sdk.jar                   # SDK API (Assert, Signals, TraceAuditor)
    opendst-patch.jar                 # Non-final VirtualThread + SimulatorThread
  apps/
    <service-a>/WEB-INF/              # Instrumented SUT classes + dependencies
    <service-b>/WEB-INF/              #   (one directory per service)
```

### Two-process model

Running the JAR starts a **parent process** (the runner) which repeatedly spawns short-lived
**child processes** (the simulations). Each child runs one simulation iteration with a different
execution plan; the parent collects results and steers future runs.

```
Parent JVM (opendst-runner)                    Child JVM (opendst-agent)
+---------------------------------+            +---------------------------------+
| Bootstrap                       |            | -javaagent:opendst-agent.jar    |
|   extracts JAR to disk          |  stdin     |                                 |
|   loads system/*.jar            | ---------> | SimulationLauncher              |
|   invokes RunnerCli             |  (JSON     |   reads META-INF/opendst/       |
|                                 |   plan)    |     deployment.json (jackson-jr)|
| RunnerCli (CLI via picocli)     |            |   creates classloader per       |
|   reads assertions.json         |  stdout    |     service from apps/          |
|   reads build-config.json       | <--------- |   calls Simulator.startNode()   |
|   spawns child JVM per plan     |  (JSON     |                                 |
|   feeds plan via stdin          |   signals) | Simulator                       |
|   parses signals from stdout    |            |   deterministic scheduler       |
|                                 |  stderr    |   intercepted time/net/threads  |
| Planner                         | <--------- |   assertion evaluation          |
|   generates execution plans     |  (human    |   state hashing                 |
|   coverage-guided exploration   |   logs)    |                                 |
|                                 |            | SimulatorAgent                  |
| ReportGenerator                 |            |   bytecode rewriting            |
|   aggregates results            |            |   JDK method interception       |
|   writes report.json            |            |                                 |
+---------------------------------+            +---------------------------------+
```

**Communication protocol:**
* **stdin** (parent to child): JSON execution plan -- seed values, segment boundaries, fault config.
* **stdout** (child to parent): structured JSON signals -- assertion verdicts, guidance data,
  lifecycle events (started, stopped, non-determinism detected).
* **stderr** (child to parent): human-readable log lines for console display.

### Parent process lifecycle

1. `Bootstrap` extracts the JAR contents to a temporary directory, builds a `URLClassLoader` from
   `system/*.jar`, and reflectively invokes `RunnerCli.main()`.
2. `RunnerCli` parses CLI arguments, scans assertion bytecode in the SUT, and creates a
   `Planner` (guided exploration or replay).
3. For each iteration, the `Planner` produces an `ExecutionPlan` (random seed + segments + fault
   config). `RunnerCli` spawns a child JVM, writes the plan to its stdin, and collects signals from
   its stdout until the child exits.
4. After all iterations, `ReportGenerator` writes `report.json` with per-assertion pass/fail counts
   and shortest-path examples.

### Child process lifecycle

1. The child JVM starts with `-javaagent:opendst-agent.jar` and
   `--patch-module java.base=opendst-patch.jar`. The agent rewrites JDK classes
   (threads, sockets, time, `System.exit`, ...) to route through deterministic interceptors.
   The patch module injects `SimulatorThread` (a `VirtualThread` subclass) into `java.base`,
   enabling `Thread` subclasses in the SUT to run as virtual threads under simulation.
   **Experimental** — this may be reverted if it causes more issues than it solves.
2. `SimulationLauncher.main()` reads the execution plan from stdin, parses `deployment.yaml`, and for
   each service creates a `URLClassLoader` pointing at its `apps/<service>/WEB-INF/` directory
   (parented to the platform classloader for isolation).
3. `Simulator.runSimulation()` starts the deterministic scheduler. Each service's `main()` method is
   invoked on a virtual thread. All threads run cooperatively on a single carrier thread, scheduled
   by a priority queue driven by the plan's random seed.
4. The simulation runs until the plan's iteration budget is exhausted or all threads are blocked.
   A final `stopped` signal with the run's state hash is emitted, and the process exits.

## Modules

```
opendst-sdk            Compile-only API for SUT code (Assert, Signals, TraceAuditor)
     ^
     |
opendst-common         Shared types used by both the agent and the maven plugin
     ^                 (deployment descriptors, assertion metadata, build config,
     |                 call-site transforms). Shaded into dependent modules.
     |
opendst-agent          Shaded Java agent -- simulation engine, bytecode rewriting,
     ^                 deterministic interceptors. Minimal dependencies (jackson-jr,
     |                 ByteBuddy). Runs in the child JVM.
     |
opendst-runner         Runner + child entry point. Runs in both JVMs:
     ^                   - Parent side: Bootstrap, RunnerCli (CLI + child JVM
     |                     management), Planner, ReportGenerator
     |                   - Child side: SimulationLauncher (parses deployment.yaml,
     |                     starts classloader-isolated nodes)
     |                 Heavier dependencies (Jackson databind + YAML, picocli).
     |
opendst-maven-plugin   Build-time only. Instruments SUT bytecode, resolves
                       dependencies, packages the self-contained JAR.
                       Also generates opendst-patch.jar (non-final VirtualThread
                       + SimulatorThread compiled from source via javac).
```

**Why is `SimulationLauncher` in `opendst-runner` and not in `opendst-agent`?** It needs Jackson
databind + YAML to parse `deployment.yaml`. The agent is a shaded JAR with only jackson-jr
(lightweight). Adding full Jackson to the agent would bloat it and risk classpath conflicts with
instrumented application code.
