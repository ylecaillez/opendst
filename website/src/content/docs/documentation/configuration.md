---
title: Maven Configuration Reference
description: All parameters for the OpenDST Maven plugin.
---

All parameters can be set in the plugin `<configuration>` block or via system properties (`-D` on the command line).

---

## Parameters

| Parameter | System Property | Default | Description |
|-----------|----------------|---------|-------------|
| `parallelism` | `opendst.parallelism` | `1` | Number of concurrent simulation JVMs. |
| `stagnationLimit` | `opendst.stagnation-limit` | `100` | Stop the session after N consecutive runs with no new signal discoveries. |
| `duration` | `opendst.duration` | `100000` | Total iteration budget per plan. Higher values mean longer, deeper simulations. |
| `test` | `opendst.test` | — | Specific test class(es) to run. Comma-separated. Supports `Class#method` syntax. |
| `includes` | `opendst.includes` | `**/*DST` | Glob patterns for test class discovery. |
| `excludes` | `opendst.excludes` | — | Glob patterns to exclude from discovery. |
| `plan` | `opendst.plan` | — | Path to a saved plan JSON file for replay. Forces parallelism=1. |
| `branchProbability` | `opendst.branchProbability` | `0.7` | Probability of branching from a known signal vs. starting a fresh random walk. |
| `replayProbability` | `opendst.replayProbability` | `0.05` | Probability of replaying a past plan for determinism verification. |
| `debug` | `opendst.debug` | — | Enable remote debugging. `true` uses `localhost:5005`. Custom value passed as JDWP agent args. Forces parallelism=1. |
| `jvmArguments` | `opendst.jvmArguments` | — | Extra JVM arguments passed to child simulation processes. |
| `logSpy` | `opendst.logSpy` | — | File path to tee simulator JSON logs to (for debugging). |
| `skipTests` | `skipTests` | `false` | Skip OpenDST test execution entirely. |
