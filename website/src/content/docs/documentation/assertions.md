---
title: Signals & Assertions
description: Assertions in OpenDST serve a dual purpose — correctness checks and exploration guides.
---

Assertions in OpenDST serve a dual purpose: they are **correctness checks** and **exploration guides**. Every assertion hit emits a signal that the orchestrator uses to navigate the state space.

---

## `Signals.ready()`

Call this after initialization is complete (sockets bound, config loaded). Fault injection is **disabled until this call**. This ensures your nodes can start up reliably before the simulator begins injecting latency, partitions, or I/O errors. Call it once per node, typically right after binding your server socket.

---

## The Assert API

All assertions are in `com.pingidentity.opendst.api.Assert`. The API module is a compile-time dependency with empty method bodies. At runtime, the simulator agent rewrites all call-sites to the actual implementation.

### Safety Assertions — "Something bad never happens"

| Method | Semantics |
|--------|-----------|
| `always(condition, message, details)` | Must be `true` every time it is reached. Must be reached at least once. Violation stops the run immediately. |
| `alwaysOrUnreachable(condition, message, details)` | Must be `true` every time it is reached. Passes even if never reached (dead code is OK). |
| `unreachable(message, details)` | Fails immediately if reached. Equivalent to `alwaysOrUnreachable(false, ...)`. |

### Liveness Assertions — "Something good eventually happens"

| Method | Semantics |
|--------|-----------|
| `sometimes(condition, message, details)` | Must be `true` at least once across the entire session (all runs). Also acts as an exploration guide. |
| `reachable(message, details)` | This code path must be reached at least once. Equivalent to `sometimes(true, ...)`. |

### Comparative Assertions

These work like `always` or `sometimes`, but compare two numeric values and include both in the signal guidance, giving the orchestrator richer data about near-misses. The orchestrator uses the distance between `left` and `right` to focus exploration toward violations — see [Distance-Guided Exploration](/opendst/documentation/exploration/#distance-guided-exploration).

| Method | Condition |
|--------|-----------|
| `alwaysGreaterThan(left, right, msg, details)` | `left > right` |
| `alwaysGreaterThanOrEqualTo(left, right, msg, details)` | `left >= right` |
| `alwaysLessThan(left, right, msg, details)` | `left < right` |
| `alwaysLessThanOrEqualTo(left, right, msg, details)` | `left <= right` |
| `sometimesGreaterThan(left, right, msg, details)` | `left > right` |
| `sometimesGreaterThanOrEqualTo(left, right, msg, details)` | `left >= right` |
| `sometimesLessThan(left, right, msg, details)` | `left < right` |
| `sometimesLessThanOrEqualTo(left, right, msg, details)` | `left <= right` |

### Grouped Assertions

Check multiple conditions as a group. Each individual condition is tracked separately in the signal guidance, giving the orchestrator per-proposition visibility.

| Method | Semantics |
|--------|-----------|
| `alwaysSome(Map<String, Boolean>, msg, details)` | At least one condition must be true every time (logical OR). Like `always(a \|\| b \|\| c)` but with per-condition tracking. |
| `sometimesAll(Map<String, Boolean>, msg, details)` | All conditions must be true simultaneously at least once (logical AND). Like `sometimes(a && b && c)` but with per-condition tracking. |

---

## Example: Assertions in Production Code

```java title="src/main/java/com/example/LeaderElection.java"
import com.pingidentity.opendst.api.Assert;

public class LeaderElection {

    public void onHeartbeat(ClusterState state) {
        // Safety: at most one leader at any time
        Assert.always(
            state.leaderCount() <= 1,
            "at-most-one-leader",
            Map.of("leaders", state.leaders()));

        // Liveness: a leader is eventually elected
        Assert.sometimes(
            state.leaderCount() == 1,
            "leader-elected",
            null);

        if (state.hasSplitBrain()) {
            // This should never happen
            Assert.unreachable("split-brain-detected",
                Map.of("partitions", state.partitions()));
        }
    }
}
```

---

## Important Rules

:::caution[Assertion messages must be string literals]
The plugin validates this during instrumentation via static bytecode analysis. Dynamic messages like `"count-" + n` will fail the build.
:::

:::tip[Assertions belong in production code]
They act as runtime invariant checks that are always active during simulation. This catches violations at the exact point where they occur.
:::

:::note[The `details` parameter]
Accepts `Map<String, Object>` for attaching context (values, state snapshots) to the signal. Pass `null` if not needed.
:::

:::note[In production]
The API methods are empty stubs with no runtime cost.
:::
