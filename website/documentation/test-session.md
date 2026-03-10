---
title: The Test Session
description: A test session is the full lifecycle from building the simulation JAR to the final report.
---

# The Test Session

A test session spans two stages: **building** the self-contained JAR with `mvn package`, then **running** it with `java -jar`. Here is what happens, step by step.

---

## Build Stage (`mvn package`)

### Instrumentation

The plugin instruments all application classes **offline** (before any simulation runs). Call-sites to JDK APIs like `new Socket()`, `Files.read()`, or `new Thread()` are rewritten to use the simulator's deterministic replacements.

During this phase, the plugin also performs **static bytecode analysis** to discover all `Assert.*` call sites and their string-literal labels. This builds a complete property catalog before any simulation starts.

### Packaging

The plugin packages everything into a **self-contained executable JAR**: instrumented application classes, the simulator agent, the orchestrator, the runner, Jackson libraries, the deployment descriptor, and the baked-in configuration defaults. This JAR can be run standalone with `java -jar`.

---

## Runtime Stage (`java -jar`)

### Simulation Loop

When the JAR is executed, the runner starts the orchestrator which enters a loop running concurrent simulations. The number of concurrent forks defaults to `availableProcessors - 1` and can be overridden with the `--forkCount` CLI argument. Each iteration:

1. The orchestrator generates a **Plan** — either a fresh random walk, a branch from a known signal, or a replay of a past plan for determinism verification.
2. A child JVM is forked with the instrumented classpath and the simulator agent. The plan is sent via stdin.
3. The orchestrator monitors stdout in real time, parsing JSON signal lines. Each new assertion signal is fed back to the orchestrator to update its signal heatmap.
4. When the child JVM exits, the orchestrator checks the exit code and determinism hash.

### Termination

The session ends when any of these conditions is met:

- **Stagnation:** `stagnationLimit` consecutive runs without discovering a new signal.
- **Failure:** An `always` assertion is violated, or a child JVM crashes.
- **Flakiness:** A replayed plan produces a different determinism hash (non-deterministic behavior detected).

### Report & Verdict

The runner generates a **JSON report** at the end of the session. It then checks all properties: every `always` property must have passed on every hit, every `sometimes` property must have been satisfied at least once, and the system must have terminated cleanly. If any check fails, the run exits with a non-zero status.

---

## Determinism Verification

:::info How determinism is verified
With probability `replayProbability` (default 5%), the orchestrator replays a past plan instead of generating a new one. The simulator computes a **state hash** by hashing every observable event (random numbers, log messages, task scheduling). If the hash differs from the original run, the test is marked **flaky** — your code has a source of non-determinism that the simulator didn't intercept.
:::
