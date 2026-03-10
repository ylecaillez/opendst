---
title: Fault Injection
description: OpenDST injects faults deterministically based on the simulation seed.
---

# Fault Injection

OpenDST injects faults **deterministically** based on the simulation seed. You configure probabilities; the simulator decides when and where to inject based on the PRNG. All faults are gated by `Signals.ready()` — no faults fire during initialization.

---

## Fault Types

### Network Faults

- **Latency:** Random delay (configurable min/max) injected before network writes.
- **Partitions:** Nodes are randomly split into two sides. All cross-partition traffic fails with `SocketException`. The partition heals after a random duration.
- **Connection Reset:** Individual TCP connections are forcefully terminated. Both endpoints are closed and all subsequent operations on the connection fail with `SocketException`. This models TCP RST behavior — the connection becomes unusable for both sides.

### Filesystem Faults

- **I/O Errors:** `IOException` thrown before any `Files.*`, `FileInputStream`, `FileOutputStream`, or `RandomAccessFile` operation.

---

## Default Configuration

Both network and filesystem faults are enabled by default with the following settings baked into the simulation JAR:

| Fault | Parameter | Default |
|-------|-----------|---------|
| **Network latency** | Min / max | `100us` / `800us` |
| **Network clogging** | Probability, max latency | `0.01`, `100ms` |
| **Filesystem I/O errors** | Probability | `0.005` |

---

## How Partitions Work

When a partition triggers, the simulator randomly assigns all nodes to side A or side B. Any network write between sides throws `SocketException`. After a random duration (configurable min/max), the partition heals.

The exact partition topology and duration are determined by the PRNG, so they are fully reproducible when replaying a plan. Partition events are logged as `fault-started` and `fault-ended` signals.
