---
title: "Architecture: Orchestrator vs Simulator"
description: OpenDST runs as two cooperating JVM processes. Understanding this split is key to understanding everything else.
---

OpenDST runs as **two cooperating JVM processes**. Understanding this split is key to understanding everything else.

---

## The Orchestrator (Control Plane)

Runs inside the self-contained `-opendst.jar` when you execute `java -jar`. It manages the **test session**: generates execution plans, forks child JVMs to run them, monitors their stdout for structured JSON signals, and uses the signal feedback to guide the next plan.

The orchestrator never executes your application code. It only observes and decides.

## The Simulator (Data Plane)

Runs in a **child JVM** with the `opendst-agent` Java agent attached. It executes a single plan: a sequence of (seed, iteration) segments that deterministically control every random choice, every timer tick, and every thread interleaving.

The simulator maintains a **discrete-event loop** where virtual time only advances when the internal scheduler runs a task. All nodes, threads, and network connections exist within this single-threaded loop.

## Communication Protocol

The orchestrator sends a **Plan** (JSON) to the simulator via **stdin**. The simulator emits structured **JSON log lines** on **stdout**. The orchestrator parses these in real time to track signal hits, detect failures, and verify determinism.

---

## Architecture Diagram

```
┌──────────────────────────────────────────────┐
│       Runner JVM (Control Plane)              │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │            Orchestrator                │  │
│  │  Plan generation, signal tracking,     │  │
│  │  branching decisions                   │  │
│  └──────────────────┬─────────────────────┘  │
└─────────────────────┼────────────────────────┘
                      │
          stdin: Plan (JSON) ↓
          stdout: Signals (JSON lines) ↑
                      │
┌─────────────────────┼────────────────────────┐
│         Child JVM (Data Plane)               │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │       Simulator (DES Loop)             │  │
│  │  Virtual time, deterministic PRNG,     │  │
│  │  event scheduling                      │  │
│  └──────────────────┬─────────────────────┘  │
│                     │                        │
│        Your Workload (Instrumented)          │
│                                              │
│    ┌───────────┐          ┌───────────┐      │
│    │  Node A   │          │  Node B   │      │
│    │ 10.0.0.1  │          │ 10.0.0.2  │      │
│    └───────────┘          └───────────┘      │
└──────────────────────────────────────────────┘
```
