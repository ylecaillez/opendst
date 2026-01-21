---
title: Exploration & Branching
description: Coverage-guided branching to systematically explore the state space around interesting discoveries.
---

Pure random exploration (fuzzing) is not enough to find deep bugs. The orchestrator uses **coverage-guided branching** to systematically explore the state space around interesting discoveries.

---

## Plans & Segments

An execution plan is a sequence of **segments**. Each segment is a `(seed, iteration)` pair that tells the simulator: "at this iteration, reseed the PRNG with this seed." A fresh random walk has a single segment. A branched plan has two or more segments — the prefix replays an exact history, and the final segment explores a new random path.

```json title="Plan structure"
{
  "segments": [
    { "seed": 7298345029345, "iteration": 0 },      // replay prefix
    { "seed": 1829374650912, "iteration": 42000 }   // explore from here
  ],
  "faults": { ... },
  "hash": 0
}
```

---

## The Branching Loop

### 1. Discovery

A run with `seed 42` hits a `sometimes` signal that has never been seen before. The orchestrator records the **shortest prefix** — the minimal sequence of segments needed to reach this signal.

```
seed:42 | start --------[ signal: "rare-path" ]-------- end
```

### 2. Fork the Past

The orchestrator replays the prefix but switches to a new seed at a random point **before** the signal. This explores whether a different history could lead to a different outcome near this interesting state.

```
seed:42 | start ---[ switch seed ]---~~ different path ~~> ???
```

### 3. Fork the Future

A second fork replays identically until **after** the signal, then switches seed. This reaches the same interesting state but explores what happens next under different random choices.

```
seed:42 | start --------[ signal ]---[ switch seed ]---~~ different future ~~> ???
```

### 4. Repeat

Each forked run can discover new signals, triggering more forks. The orchestrator builds a tree of increasingly targeted exploration.

---

## Signal Selection: Tournament Scoring

When branching, the orchestrator picks which signal to branch from using **tournament selection**: it samples 10 random signals and picks the one with the highest score.

```
score = 10^depth / (1 + explorationCount) * distanceBoost * minorityBoost
```

| Factor | Meaning |
|--------|---------|
| **Depth** | Number of segments in the prefix. Deeper prefixes indicate more interesting states — the orchestrator had to chain multiple branching decisions to reach them. Exponential weighting (`10^depth`) strongly favors progress. |
| **Exploration count** | How many times the orchestrator has already branched from this signal+condition. Penalizes over-explored signals to spread the search. |
| **Distance boost** | For [comparative assertions](#distance-guided-exploration), amplifies signals that are close to violation. Ranges from 1x (far from violation) to 10x (at the boundary). Omitted (1x) for non-comparative signals. |
| **Minority boost** | For [condition-balanced exploration](#condition-balanced-exploration), amplifies the less-frequently-observed condition outcome. Ranges from 1x (balanced) to 10x (very rare). Omitted (1x) when only one outcome has been observed. |

---

## Distance-Guided Exploration

Comparative assertions like `alwaysGreaterThan(left, right, ...)` carry richer information than a boolean pass/fail. The assertion knows *how close* the system came to a violation — the **distance** `|left - right|`. The orchestrator uses this distance to steer exploration toward counter-examples.

### How It Works

Every comparative assertion emits a **guidance** payload alongside its pass/fail result:

```json title="Signal emitted by alwaysGreaterThan(balance, 0, ...)"
{
  "type": "assert",
  "kind": "always",
  "message": "balance-stays-positive",
  "condition": true,
  "guidance": { "left": 3, "right": 0 }
}
```

The orchestrator extracts the distance (`|3 - 0| = 3.0`) and tracks the **minimum distance** ever observed for each signal label. When a run produces a smaller distance than any previous run, three things happen:

1. **The prefix is updated.** The plan that reached this near-miss becomes the new branching point for this signal, replacing any previously saved prefix — even if it's longer. A longer path that nearly violates the invariant is more valuable than a shorter path that's far from the boundary.

2. **The signal's score increases.** The distance boost formula is:

    ```
    distanceBoost = 1 + 9 / (1 + distance)
    ```

    At `distance = 0` (right at the boundary), the boost is 10x. At `distance = 9`, it's 2x. At `distance = 99`, it's ~1.1x. This smoothly focuses exploration on signals that are closest to violation.

3. **A `narrowed` log signal is emitted**, visible in the build output:

    ```
    signal type:narrowed    seed:a1b2c3d4e5f67890 iteration:42000 distance:3.0000 signal:balance-stays-positive
    ```

### The Feedback Loop

The distance-guided mechanism creates a **gradient descent** over the state space:

```
Run 1: balance=100, distance=100  →  save prefix (at iteration 42000)
Run 2: balance=50,  distance=50   →  narrowed! update prefix, boost score
Run 3: balance=12,  distance=12   →  narrowed! update prefix, boost more
Run 4: balance=3,   distance=3    →  narrowed! update prefix, boost to ~3.5x
Run 5: balance=-1,  distance=N/A  →  VIOLATION FOUND — condition is false
```

Each time the distance narrows, the orchestrator branches more aggressively from that prefix. The exponential score boost ensures that signals closest to violation win the tournament selection, concentrating exploration where it matters most.

### Branching With Rewind

For non-comparative signals, the orchestrator branches at the **exact iteration** where the signal was hit — replaying the same history and then exploring a different future. This is "fork the future" exploration.

For distance-guided signals, this strategy doesn't work well. The values of `left` and `right` at iteration N are the result of choices made *before* N. Branching at N replays the exact same history that produced those values, then explores what happens next — but the distance can only change if earlier choices change.

Instead, the orchestrator **rewinds** to a uniformly random point in `[0, iteration)` before branching:

```
Prefix saved at iteration 42000 (where distance was 3.0)

Non-comparative branch:
  seed:X | replay ------[ signal@42000 ]---[ new seed ]---~~ explore future ~~>

Distance-guided branch (rewind to random point):
  seed:X | replay --[ rewind@28000, new seed ]---~~ different path ~~> assertion fires with different left/right
```

Some rewinds will diverge early (large structural changes to the execution), others late (small perturbations near the assertion). This diversity is important — the orchestrator doesn't know *which* earlier choices influence the distance, so it tries many different rewind points. The distance boost scoring ensures it keeps retrying: if a rewind doesn't narrow the distance, the signal's score remains high and it will be selected again with a different rewind point.

### Which Assertions Support This?

All comparative assertions emit guidance. Boolean assertions (`always`, `sometimes`) do not — they have no numeric values to measure distance from.

| Assertion | Distance meaning |
|-----------|-----------------|
| `alwaysGreaterThan(left, right, ...)` | `\|left - right\|` — how far `left` is from being `<= right` |
| `alwaysGreaterThanOrEqualTo(left, right, ...)` | `\|left - right\|` — how far `left` is from being `< right` |
| `alwaysLessThan(left, right, ...)` | `\|left - right\|` — how far `left` is from being `>= right` |
| `alwaysLessThanOrEqualTo(left, right, ...)` | `\|left - right\|` — how far `left` is from being `> right` |
| `sometimesGreaterThan(left, right, ...)` | Same distance, but guides toward *satisfying* the condition |

:::tip[Use comparative assertions for numeric invariants]
Instead of `Assert.always(balance > 0, "positive-balance", ...)`, prefer `Assert.alwaysGreaterThan(balance, 0, "positive-balance", ...)`. Both express the same property, but the comparative form gives the orchestrator a gradient to follow instead of a flat boolean landscape. This can dramatically reduce the number of runs needed to find a violation.
:::

---

## Condition-Balanced Exploration

Every assertion evaluates to either `true` or `false` on each hit. The orchestrator tracks both outcomes independently, saving a separate prefix for each and counting how often each occurs. When one outcome is much rarer than the other, the orchestrator boosts its score to preferentially branch from it.

### Why It Matters

Consider a `sometimes` assertion that is `true` 999 times out of 1000 and `false` only once. The rare `false` outcome represents an under-explored region of the state space — it might be the path to a violation, but the orchestrator would rarely branch from it without intervention.

Similarly, an `always` assertion that is `false` only 1/1000 times has found a near-violation path. The orchestrator should aggressively branch from that rare `false` prefix to find more paths that violate the invariant.

### How It Works

The orchestrator maintains two prefixes per signal — one for `condition=true` and one for `condition=false`. Each is an independent branch candidate with its own exploration count and hit count.

When scoring candidates for tournament selection, the minority outcome gets a boost:

```
minorityBoost = 1 + 9 * (1 - 2 * ratio)
```

Where `ratio` is the proportion of total hits that produced this condition outcome. The boost is clamped to `[1, 10]`:

| Ratio (this outcome / total) | Minority Boost |
|------------------------------|---------------|
| 0.001 (very rare)            | ~10x          |
| 0.1 (uncommon)               | 8.2x          |
| 0.2                          | 4.6x          |
| 0.3                          | 2.8x          |
| 0.5 (balanced)               | 1x            |
| >0.5 (majority)              | 1x            |

### Interaction With Distance-Guided Exploration

Condition-balanced and distance-guided exploration are complementary:

- **Distance** guides *how close* a comparative assertion is to the violation boundary. It answers: "are we almost there?"
- **Condition balance** guides *which outcome* to branch from. It answers: "are we exploring both sides?"

A comparative assertion like `alwaysGreaterThan(balance, 0, ...)` benefits from both: distance-guided exploration narrows the gap between `balance` and `0`, while condition-balanced exploration ensures that if `balance` does go negative (a rare `false` outcome), the orchestrator aggressively branches from that discovery.

### Log Output

Exploration runs now include the condition in the signal label:

```
   run type:explore     seed:a1b2c3d4e5f67890 score:82.00 starting-at:42000 duration:8000 signal:balance-positive:false
```

The `:true` or `:false` suffix indicates which condition outcome the orchestrator is branching from.

---

## Branch vs Random Walk

The `branchProbability` parameter (default 0.7) controls the mix: 70% of runs branch from a known signal, 30% start fresh random walks. Fresh random walks ensure the orchestrator doesn't get stuck in a local minimum. When no signals have been discovered yet, all runs are random walks.

---

## Replaying Failures

When a bug is found, the exact plan (all segments + fault config) is saved to `target/opendst/<Test>/failures/failure-N.json`. Replay it with:

```bash
mvn verify -Dopendst.test=com.example.MyDST \
    -Dopendst.plan=target/opendst/MyDST/failures/failure-0.json
```

This replays the exact same sequence of random choices, the same fault injections, and the same thread interleavings. Set breakpoints and debug with full reproducibility.
