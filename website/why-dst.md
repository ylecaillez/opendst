---
title: "Tutorial: Finding bugs your tests won't"
description: Build a distributed bank transfer system and watch OpenDST find a real bug in under a second.
---

# Tutorial: Finding bugs your tests won't

*Build a distributed bank transfer system and watch OpenDST find a real bug in under a second.*

---

## The bug

Two bank accounts, Alice and Bob, each start with $1,000. A coordinator transfers random amounts between them. After every transfer, it checks: does Alice + Bob still equal $2,000?

The code is simple, the logic is obvious, and the tests pass. You deploy it.

Three weeks later, a customer reports that money has disappeared.

You spend two days on the RCA. The root cause: a network timeout during a credit operation. The debit went through, but the credit didn't. The money vanished. Your retry logic — designed to handle exactly this — made things worse by double-crediting when the original request actually did succeed on the server.

Your integration tests never caught this because they run on `localhost`, where the network doesn't fail.

What about chaos testing? It *could* find this bug — in theory. In practice, it means setting up a fault injection proxy (Toxiproxy, Chaos Monkey, Istio fault rules), configuring it to target the right connection at the right time, waiting through real-time timeouts (a 2-second socket timeout means 2 real seconds per attempt), and running enough iterations to stumble into the right sequence. When it finally triggers the bug, reproducing it is its own adventure — you need the exact same fault timing, the exact same request ordering, on the exact same infrastructure.

This isn't a contrived example. This is the class of bug that takes down real distributed systems. And we're going to find it in under a second, reproducibly.

---

## Why traditional tests don't find this

Consider the state space of our transfer system. Each transfer involves:
- Random direction (Alice to Bob, or Bob to Alice)
- Random amount ($1–$100)
- Network conditions (normal, slow, partitioned, reset)
- Timing of each TCP segment (SYN, data, ACK, FIN)
- Thread scheduling order between the coordinator, Alice's server, and Bob's server

A single transfer has thousands of possible execution paths. Twenty transfers have more paths than atoms in the universe. Your test suite, no matter how thorough, covers a handful.

| Test type | What it covers | Finds our bug? |
|---|---|---|
| Unit tests | Single function, mocked dependencies | No — the bug is in the interaction |
| Integration tests | Happy path, localhost network | No — network always succeeds |
| Chaos testing | Real failures on real infrastructure | Maybe — but complex setup, real-time waits, and hard to reproduce |
| **DST** | **All of the above, thousands of paths/second** | **Yes — and 100% reproducible** |

Deterministic Simulation Testing replaces the real environment — time, network, filesystem, threads, randomness — with controlled, deterministic versions. A 2-second socket timeout takes nanoseconds. A network partition is injected at exactly the right moment. And when a bug is found, the exact execution can be replayed, step by step, to debug it. No infrastructure to set up — just `java -jar`.

---

## Let's build it

You'll need **JDK 25+** and **Maven**. The full source is in the [`opendst-examples/example-bank-transfer`](https://github.com/pingidentity/opendst/tree/main/opendst-examples/example-bank-transfer) directory.

### The account server

Each account is a TCP server that processes DEBIT, CREDIT, and BALANCE commands:

```java
public final class AccountServer {

    public static final int CMD_BALANCE = 1, CMD_DEBIT = 2, CMD_CREDIT = 3;
    public static final int STATUS_OK = 0, STATUS_INSUFFICIENT_FUNDS = 1;

    private int balance;

    public static void main(String[] args) throws IOException {
        var port = Integer.parseInt(args[0]);
        var initialBalance = Integer.parseInt(args[1]);
        var server = new AccountServer(initialBalance);
        Signals.ready();                  // Tell the simulator: I'm initialized, start injecting faults
        server.serve(port);
    }

    public void serve(int port) throws IOException {
        try (var ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            while (true) {
                try (var socket = ss.accept();
                     var in = new DataInputStream(socket.getInputStream());
                     var out = new DataOutputStream(socket.getOutputStream())) {
                    int command = in.readInt();
                    int amount = in.readInt();
                    handleCommand(command, amount, out);
                }
            }
        }
    }
    // ... handleCommand debits, credits, or returns balance
}
```

Notice `Signals.ready()`. This is the only OpenDST-specific call in the server. It tells the simulator: "I'm done initializing — you can start injecting faults now." Without it, the simulator might inject a network partition before the server has opened its socket.

### The transfer service (where the bug lives)

```java
public final class TransferService {

    public boolean transfer(int amount) {
        // Step 1: Debit the source
        int debitStatus = sendCommand(sourceHost, sourcePort, CMD_DEBIT, amount);
        if (debitStatus != STATUS_OK) return false;

        // Step 2: Credit the destination
        // BUG: If this fails, the money is gone. No rollback.
        // BUG: If this times out but the server processes it, the retry double-credits.
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                int creditStatus = sendCommand(destHost, destPort, CMD_CREDIT, amount);
                if (creditStatus == STATUS_OK) return true;
            } catch (IOException e) {
                // Retry...
            }
        }
        return false; // All retries failed. Money has been destroyed.
    }
}
```

This is production-quality-looking code. It has retries. It handles errors. But it doesn't handle the case where a debit succeeds and all credit attempts fail. There's no compensation, no two-phase commit, no idempotency key. In a real codebase, this might survive for months before someone notices the balance drift.

### The coordinator (with assertions)

The coordinator performs random transfers and checks the invariant after each one:

```java
public final class Coordinator {

    private static final int EXPECTED_TOTAL = 2000;  // alice(1000) + bob(1000)

    public static void main(String[] args) throws InterruptedException {
        // ... parse args, sleep to let servers start
        Signals.ready();

        var random = new Random();
        for (int i = 0; i < numTransfers; i++) {
            boolean aliceToBob = random.nextBoolean();
            int amount = random.nextInt(1, 101);
            new TransferService(fromHost, fromPort, toHost, toPort).transfer(amount);
            checkInvariant(aliceHost, alicePort, bobHost, bobPort);
        }
    }

    private static void checkInvariant(String aliceHost, int alicePort,
                                        String bobHost, int bobPort) {
        int alice = queryBalance(aliceHost, alicePort);
        int bob   = queryBalance(bobHost, bobPort);
        int total = alice + bob;

        Assert.always(
            total == EXPECTED_TOTAL,
            "total balance conserved",
            Map.of("alice", alice, "bob", bob, "total", total, "expected", EXPECTED_TOTAL));

        Assert.always(
            alice >= 0 && bob >= 0,
            "no negative balance",
            Map.of("alice", alice, "bob", bob));
    }
}
```

`Assert.always(condition, name, details)` is the key construct. It tells the orchestrator: "this condition must hold true on every execution path, in every simulation run." If it ever fails — on any seed, any schedule, any fault injection scenario — the orchestrator saves the exact plan that triggered the failure so you can replay it.

The `details` map is attached to the report when the assertion fires. When it fails, you'll see exactly which balances caused the invariant violation.

### The deployment descriptor

```yaml
services:
  alice:
    class: com.pingidentity.opendst.example.bank.AccountServer
    ip: 10.0.0.1
    args: ["8001", "1000"]
  bob:
    class: com.pingidentity.opendst.example.bank.AccountServer
    ip: 10.0.0.2
    args: ["8002", "1000"]
  coordinator:
    class: com.pingidentity.opendst.example.bank.Coordinator
    ip: 10.0.0.3
    args: ["10.0.0.1", "8001", "10.0.0.2", "8002", "20"]
```

Each service gets its own virtual IP address. All TCP traffic between them goes through the simulator's virtual network, where latency, partitions, and connection resets are injected automatically.

### The Maven plugin

```xml
<dependencies>
    <dependency>
        <groupId>com.pingidentity.opendst</groupId>
        <artifactId>opendst-sdk</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>com.pingidentity.opendst</groupId>
            <artifactId>opendst-maven-plugin</artifactId>
            <version>0.0.1-SNAPSHOT</version>
            <executions>
                <execution>
                    <goals><goal>build</goal></goals>
                    <configuration>
                        <descriptor>${project.basedir}/deployment.yaml</descriptor>
                        <parallelism>8</parallelism>
                        <stagnationLimit>200</stagnationLimit>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

The `build` goal (bound to the `package` phase) does three things:

1. **Instruments** your bytecode — rewrites `Assert.*` and `Signals.*` stub calls to the actual simulation implementation.
2. **Discovers** all assertions via static analysis — `"total balance conserved"`, `"no negative balance"`, plus the built-in lifecycle assertions.
3. **Packages** everything into a self-contained `-opendst.jar` — your application, the simulator, the orchestrator, and all dependencies.

The output is a single executable JAR. The plugin does not run simulations.

---

## Run it

```bash
mvn package
java -jar target/example-bank-transfer-0.0.1-SNAPSHOT-opendst.jar
```

The orchestrator starts exploring. Each run uses a different seed, producing a different execution path: different transfer directions, different amounts, different network fault timings. Hundreds of paths are explored per second.

Within moments, the orchestrator finds a seed where:

1. The coordinator debits Alice $73.
2. The simulator injects a network partition between the coordinator and Bob.
3. The credit to Bob fails.
4. The retry also fails.
5. The coordinator checks the invariant: Alice has $927, Bob has $1,000. Total: $1,927.
6. `Assert.always("total balance conserved")` fires with `pass: false`.

The orchestrator saves the exact plan — the seed, the fault injection decisions, the scheduling order — to a file. You can replay it:

```bash
java -jar target/example-bank-transfer-0.0.1-SNAPSHOT-opendst.jar --fail-fast
```

Same seed, same faults, same bug, same assertion failure. 100% reproducible. Set a breakpoint in your IDE on the `transfer()` method, run it again, and step through the exact execution that caused the failure.

The report (`report.json`) summarizes what was found:

```json
{
  "count": 847,
  "duration": "00:00:03",
  "assertions": [
    {
      "name": "total balance conserved",
      "pass": "fail",
      "examples": {
        "passCount": 12650,
        "passExamples": [ ... ],
        "failCount": 42,
        "failExamples": [
          {
            "planFile": "plans/plan-0017.json",
            "iteration": 3,
            "details": { "alice": 927, "bob": 1000, "total": 1927, "expected": 2000 }
          }
        ]
      }
    },
    {
      "name": "no negative balance",
      "pass": "pass",
      "examples": { "passCount": 12692, "failCount": 0, ... }
    }
  ]
}
```

847 simulation runs in 3 seconds. The `"total balance conserved"` assertion failed 42 times. The first failure is at iteration 3, plan `plan-0017.json`, and the details tell you exactly what happened: Alice has $927, Bob has $1,000, total is $1,927 instead of $2,000.

---

## How it works under the hood

When you call `java -jar`, the JAR's runner starts the orchestrator, which spawns child JVMs with the `SimulatorAgent` attached. Each child JVM runs one simulation.

### Virtual time

`System.currentTimeMillis()`, `System.nanoTime()`, `Thread.sleep()`, and `Instant.now()` are all redirected to the simulator's virtual clock. The clock only advances when the simulator decides — typically to the next scheduled event. A `Thread.sleep(Duration.ofHours(1))` completes in nanoseconds of wall-clock time.

### Deterministic scheduling

All threads — including Virtual Threads — are managed by the simulator's single-threaded event loop. The scheduler picks which thread runs next based on the seed. This means thread interleavings that would take hours to reproduce by hand are explored systematically.

### Simulated network

Each node gets a virtual IP. TCP connections go through the simulator's network stack, which injects:
- **Latency**: 100μs to 800μs per packet (configurable)
- **Clogging**: 1% chance of adding up to 100ms additional delay
- **Connection resets**: triggered by partition events

No real sockets are opened. The entire network exists inside the JVM.

### Fault injection

Faults are injected *after* all nodes have called `Signals.ready()`. This ensures deterministic startup before the chaos begins. The simulator controls exactly when and where faults occur, guided by the seed.

### Seed-based replay

Every decision the simulator makes — scheduling order, fault timing, random values — is derived from a single seed. Same seed = same execution = same bug. This is what makes DST fundamentally different from chaos testing: bugs don't slip away when you try to reproduce them.

---

## The assertion model

OpenDST uses **properties**, not expected values. You don't write `assertEquals(2000, total)` — you write `Assert.always(total == 2000, "conserved", ...)`. The difference matters:

### Invariants (must always hold)

```java
Assert.always(condition, "name", details);
```
Fails the simulation if the condition is ever false. Must be reached at least once across all runs.

```java
Assert.alwaysOrUnreachable(condition, "name", details);
```
Same, but it's OK if the assertion is never reached (dead code path).

```java
Assert.unreachable("name", details);
```
Fails if this code is ever executed.

### Liveness (must eventually hold)

```java
Assert.sometimes(condition, "name", details);
```
Across all runs in the session, this condition must be true at least once. Used to verify that the exploration is actually reaching interesting states.

```java
Assert.reachable("name", details);
```
This code path must be reached at least once across all runs.

### Signals

```java
Signals.ready();
```
Tells the simulator the node is initialized. Fault injection begins after all nodes are ready.

These are empty stubs in `opendst-sdk`. The Maven plugin rewrites all call-sites to the actual simulation implementation during the build. Your production code compiles and runs normally without OpenDST — the SDK dependency has zero runtime cost.

---

## Guided exploration

Pure random exploration — like pressing random buttons in a video game — rarely reaches interesting states. The orchestrator uses your assertions to guide the search.

1. **Run with seed 42** — The simulator executes your system. The coordinator hits `Assert.always("total balance conserved")` and it passes. But it also hits `Assert.reachable("server-received")` for the first time.

2. **Branch** — The orchestrator creates new runs from seed 42: one that changes the seed *before* the new signal (explore the past differently), and one that changes it *after* (explore the future differently).

3. **Deeper** — Each branch can discover new signals, triggering more branches. The orchestrator builds a tree of increasingly targeted exploration around the most interesting parts of your state space.

The `stagnationLimit` parameter controls when to stop: if no new signals are discovered after 200 consecutive runs, the session ends. The `parallelism` parameter controls how many simulations run concurrently.

---

## Next steps

You've seen how OpenDST finds a real distributed systems bug — money loss in a naive transfer service — that traditional tests miss. The fix? Add a two-phase commit, or idempotency keys, or a compensation log. Then run it again and let the simulator try to break your fix.

- [Architecture](/documentation/architecture) — How the orchestrator, simulator, and agent fit together
- [Writing Tests](/documentation/writing-tests) — Patterns for assertions, TraceAuditors, and deployment descriptors
- [Exploration & Branching](/documentation/exploration) — Deep dive into the guided exploration algorithm
- [Fault Injection](/documentation/faults) — What faults are injected and how
- [Configuration](/documentation/configuration) — Build parameters and runtime defaults
