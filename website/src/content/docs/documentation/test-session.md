---
title: The Test Session
description: A test session is the full lifecycle from mvn verify to the final HTML report.
---

A test session is the full lifecycle from `mvn verify` to the final HTML report. Here is what happens, step by step.

---

## Phase 1: Instrumentation

The plugin instruments all application and test classes **offline** (before any simulation runs). Call-sites to JDK APIs like `new Socket()`, `Files.read()`, or `new Thread()` are rewritten to use the simulator's deterministic replacements.

During this phase, the plugin also performs **static bytecode analysis** to discover all `Assert.*` call sites and their string-literal labels. This builds a complete property catalog before any simulation starts.

## Phase 2: Test Discovery

The plugin scans `target/test-classes` for classes matching `**/*DST` (or your custom `includes`/`excludes` patterns). Within each class, it discovers all `public void` no-arg methods. Each class+method pair becomes a separate test to run.

## Phase 3: Simulation Loop

For each test, the orchestrator enters a loop running up to `parallelism` simulations concurrently. Each iteration:

1. The orchestrator generates a **Plan** — either a fresh random walk, a branch from a known signal, or a replay of a past plan for determinism verification.
2. A child JVM is forked with the instrumented classpath and the simulator agent. The plan is sent via stdin.
3. The orchestrator monitors stdout in real time, parsing JSON signal lines. Each new assertion signal is fed back to the orchestrator to update its signal heatmap.
4. When the child JVM exits, the orchestrator checks the exit code and determinism hash.

## Phase 4: Termination

The session ends when any of these conditions is met:

- **Stagnation:** `stagnationLimit` consecutive runs without discovering a new signal.
- **Failure:** An `always` assertion is violated, or a child JVM crashes.
- **Flakiness:** A replayed plan produces a different determinism hash (non-deterministic behavior detected).

## Phase 5: Report & Verdict

The plugin generates an **HTML report** and a **JSON report** at `target/opendst/<TestClass>/report.html`. It then checks all properties: every `always` property must have passed on every hit, every `sometimes` property must have been satisfied at least once, and the system must have terminated cleanly. If any check fails, the build fails.

---

## Determinism Verification

:::note[How determinism is verified]
With probability `replayProbability` (default 5%), the orchestrator replays a past plan instead of generating a new one. The simulator computes a **state hash** by hashing every observable event (random numbers, log messages, task scheduling). If the hash differs from the original run, the test is marked **flaky** — your code has a source of non-determinism that the simulator didn't intercept.
:::
