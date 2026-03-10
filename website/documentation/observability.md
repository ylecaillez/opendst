---
title: Reports & Observability
description: JSON reports and the structured log protocol.
---

---

## JSON Reports

At the end of each test session, the runner generates a report as `report.json`. The report contains:

- **Total plans executed** and total violations.
- **System health:** Whether the simulator terminated cleanly across all runs.
- **Property table:** Every assertion property with its label, type (`always`/`sometimes`/`alwaysOrUnreachable`), hit count, first iteration hit, source location (class + line), pass/fail status, and structured `details` from the assertion call.
- **First pass/fail details:** The `details` map from the first passing and first failing hit for each property, for debugging.

---

## Structured Log Protocol

Every event in the simulation is emitted as a minified, single-line JSON object on stdout. The orchestrator parses these in real time.

```json
{
  "lid": 42,              // Log sequence ID for this run
  "source": "simulator",  // "simulator" (framework) or "vhost" (workload)
  "vhost": "server-1",    // Node hostname (omitted for lifecycle)
  "it": 1050,             // Iteration (PRNG step count)
  "log": { ... }          // Payload (varies by type)
}
```

---

## Log Types

| Type | Source | Key Fields | Description |
|------|--------|------------|-------------|
| `lifecycle` | simulator | `message`, `reason`, `hash` | Simulation started/stopped. The `hash` in the "stopped" message is the determinism hash for flakiness detection. |
| `assert` | simulator | `message`, `kind`, `condition`, `details`, `guidance` | An assertion was evaluated. `kind` is `always`/`alwaysOrUnreachable`/`sometimes`. `condition` is the boolean result. |
| `fault` | simulator | `lifecycle`, `fault`, `fault-id` | Fault injection event. `lifecycle` is `fault-started`/`fault-ended`/`fault-triggered`. |
| `stdout` | vhost | `message` | Application code wrote to `System.out` or `System.err`. Captured per-node. |

---

## The Feedback Loop

- **Signal discovery:** The orchestrator parses `assert` logs in real time. New signals update the signal heatmap, exploration counts, and shortest-prefix map. This guides `nextPlan()`.
- **Failure detection:** Non-zero exit codes or `always` violations stop the session. The plan is saved for replay.
- **Determinism verification:** Replayed plans compare the `hash` from the `lifecycle/stopped` event. A mismatch means non-deterministic behavior was detected.
