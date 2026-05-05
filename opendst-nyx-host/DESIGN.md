# OpenDST nyx-lite Engine — Design Document

## Overview

The nyx-lite engine replaces the cold-fork execution model with VM snapshot/restore.
Instead of spawning a fresh JVM process per iteration, a single JVM runs inside a
Firecracker microVM. After initial warmup the VM is snapshotted; each subsequent
iteration restores to a saved snapshot (cheap: ~20 ms for dirty-page restore) and
runs the simulation from that warm point.

## Motivation and gains

| Model           | Per-iteration cost                        |
|-----------------|-------------------------------------------|
| Cold fork        | ~2–4 s (JVM start + class loading)       |
| nyx-lite (depth 0) | ~20 ms (dirty-page restore) + simulation |
| nyx-lite (depth N) | < 20 ms + tail of simulation           |

The snapshot tree (see below) extends this: when two plans share N identical prefix
segments, depth-N restores skip N segment executions entirely.

---

## Repository layout

```
opendst/
  opendst-agent/            Java agent — randomness interception + nyx hooks
    RandomInterceptors.java — Source.next(), NYX_SEGMENT_SUPPLIER, NYX_BOOT_SNAPSHOT_PENDING
    Simulator.java          — checkSegmentHash() issues RequestSnapshot hypercall
    NyxSegmentHypercall.java — reflection bridge: agent → nyx-guest.jar (system classpath)

  opendst-nyx-host/
    src/main.rs             — Rust shim: drives the VM, manages snapshot tree
    guest/
      hypercall.c           — JNI native: issues int3 hypercall ABI
      Makefile              — builds libhypercall.so + nyx-guest.jar
      src/opendst/nyx/guest/
        Hypercall.java      — JNI wrapper, hypercall opcodes
        NyxGuestEntry.java  — guest main(): wires SHM, takes initial snapshot, runs simulations
        SharedMemory.java   — two direct ByteBuffers registered as shared memory regions
        SharedOutputStream.java — System.out redirect; detects "stopped" → EXECDONE
    base-image/
      Dockerfile            — Alpine + JDK + hypercall bridge; opendst-runner.jar baked in
      build-base-image.sh   — assembles and builds opendst-nyx-base:latest

  opendst-runner/
    NyxImageManager.java    — Docker image → ext2 rootfs extraction; vmconfig.json generation
    NyxBackend.java         — ExecutionBackend: drives shim via stdin/stdout
    BuildRunner.java        — CLI: --engine=nyx-lite --image=<tag>

  opendst-maven-plugin/
    BuildMojo.java          — opendst:build goal; --engine=nyx-lite builds Docker image
```

External dependency: `~/git/nyx-lite` — a fork of Firecracker with snapshot/restore
and a hypercall ABI. The shim links against it as a Rust library.

---

## Docker image layers

```
opendst-nyx-base:latest
  FROM alpine
  + JDK 25
  + OpenRC / SSH
  + /usr/bin/opendst-nyx-shim   ← host-side Rust binary (runs outside the VM)
  + /resources/libhypercall.so  ← guest-side JNI native
  + /resources/nyx-guest.jar    ← guest-side Java (NyxGuestEntry, Hypercall, SharedMemory, …)
  + /opendst-runner.jar         ← fat jar with OpenDSTExecutor and the agent

<app>-opendst-nyx:<version>
  FROM opendst-nyx-base:latest
  + /opendst-deployment/        ← app classes + system/*.jar (built by opendst:build goal)
```

The `opendst:build` Maven goal with `engine=nyx-lite` produces the second layer.
`NyxImageManager` extracts it at run time: `docker export | tar` → staging dir →
`genext2fs` → `rootfs.ext4`, cached under `~/.opendst/nyx-rootfs/<digest>/`.

The shim binary (`/usr/bin/opendst-nyx-shim`) is also extracted from the image so
the host-side binary exactly matches the guest-side libraries.

---

## Hypercall ABI

The guest communicates with the host via `int3` with registers set per the nyx-lite
protocol (defined in `nyx-lite/examples/test_guest_runner.rs`):

```
RAX = 0x6574696c2d78796e  ("nyx-lite")
R8  = hypercall_num
R9  = arg1 (usually a pointer or value)
R10, R11, R12 = arg2, arg3, arg4
```

Opcodes used by OpenDST:

| Name           | Value              | Direction    | Meaning                                    |
|----------------|--------------------|--------------|--------------------------------------------|
| `EXECDONE`     | `0x656e6f6463657865` | guest→host | Iteration complete; host reads output      |
| `SNAPSHOT`     | `0x746f687370616e73` | guest→host | Take / restore initial snapshot            |
| `BOOT_SNAPSHOT`| `0x706e73746f6f6273` | guest→host | Deferred deep snapshot (first random draw) |
| `SHAREMEM`     | `0x6d656d6572616873` | guest→host | Register a shared memory region            |
| `DBGPRINT`     | `0x746e697270676264` | guest→host | Debug string to host stderr               |
| `FAILTEST`     | `0x747365746c696166` | guest→host | Fatal error; host logs and aborts          |

---

## Shared memory layout

Two 64 KB `ByteBuffer.allocateDirect()` regions, registered via `SHAREMEM` hypercall:

```
"opendst-in"  (64 KB):
  [0..3]   4-byte LE length of plan JSON
  [4..N]   plan JSON bytes (UTF-8)
  --- reused between segment transitions ---
  [0..7]   8-byte LE seed for next segment
  [8..15]  8-byte LE iteration boundary for next segment
  (all-zeros = end-of-plan sentinel)

"opendst-out" (64 KB):
  series of newline-terminated UTF-8 log lines
  followed by a NUL byte (written by SharedMemory.finishIteration())
  --- host reads from a cursor that persists across iterations ---
```

The `output` region is never fully zeroed on restore. Instead the shim stores the
flush cursor alongside each snapshot and calls `partial_zero_output(from_cursor)`,
leaving the pre-snapshot preamble intact so the guest's `ByteBuffer.position()` stays
consistent with what the host has already read.

---

## Boot sequence

```
Host (NyxImageManager / NyxBackend)          Guest (NyxGuestEntry.main)
─────────────────────────────────────        ──────────────────────────────────────
start shim process                   →
                                             load system classloader jars
                                             warm up: Class.forName(OpenDSTExecutor)
                                             allocate SharedMemory (2 × ByteBuffer)
                                             SHAREMEM "opendst-in"   ──────────────→ in_vaddr recorded
                                             SHAREMEM "opendst-out"  ──────────────→ out_vaddr recorded
                                             wire NYX_SEGMENT_SUPPLIER
                                             set NYX_BOOT_SNAPSHOT_PENDING = true
                                             SNAPSHOT ───────────────────────────→ boot_to_snapshot():
                                                                                    initial snap taken
                                                                                    (empty output buf)
← "ready\n" emitted

─── per-iteration loop ──────────────────────────────────────────────────────────────

write plan JSON to "opendst-in"      →
apply_snapshot(restore_snap)
partial_zero_output(restore_cursor)
vm.run()                             →
                                             (restored to initial snapshot)
                                             resetOutput() + readPlan()
                                             setIn(planBytes)
                                             setOut(SharedOutputStream)
                                             OpenDSTExecutor.main()
                                               → new Simulator(plan)
                                                   → "started" written to output
                                               → Source.next() [iteration=0]
                                                   NYX_BOOT_SNAPSHOT_PENDING=true
                                                   → NYX_BOOT_SNAPSHOT_PENDING=false
                                                   BOOT_SNAPSHOT ──────────────────→ flush_output (cursor=N)
                                                                                      deep snap taken at N
                                                                                      write segment[0] to input
                                               ← re-seed from input (NYX_SEGMENT_SUPPLIER)
                                               → simulation runs …
                                               → at segment boundary:
                                                   log "segment-completed" + hash
                                                   SNAPSHOT ──────────────────────→ flush_output
                                                                                      extract hash → key
                                                                                      snap cached at (key, cursor)
                                                                                      write next segment to input
                                               ← re-seed
                                               → "stopped" written to output
                                                   → SharedOutputStream.flushLine()
                                                      EXECDONE ──────────────────→ ExecDone: flush_output
                                                                                    return from vm.run()
← "SHIM_DONE\n" emitted
```

Second and subsequent iterations restore from the deep boot snapshot (cursor=N,
depth=0) or a segment snapshot (cursor=M, depth=K), skipping everything before that
point.

---

## Snapshot tree

Snapshots are keyed by `Vec<u32>` — the sequence of segment hash values observed at
each segment boundary, in order. The boot snapshot lives at `vec![]`.

```
vec![]          → boot snapshot  (after plan parsing + node init, cursor=N)
vec![h1]        → after segment 1 completes
vec![h1, h2]    → after segment 2 completes
…
```

On each new plan the shim:
1. Parses the prefix hashes from the plan JSON (all segments except the last, which
   is exploratory and has hash=0).
2. Walks the cache from longest prefix down until a match is found.
3. Restores from the deepest matching snapshot, skipping already-computed work.

The cursor stored with each snapshot tells the shim where to start reading new output
and how much of the output buffer to preserve on the next restore.

---

## Per-iteration timing budget (measured)

| Phase                     | Time      |
|---------------------------|-----------|
| `apply_snapshot` (KVM dirty-page restore) | ~20 ms |
| `partial_zero_output` (64 KB − cursor)    | ~50 µs |
| simulation execution (100k steps)         | ~1.3 s  |
| `write_plan` + read stdin                 | ~10 ms  |

The 20 ms restore cost comes from KVM's `KVM_GET_DIRTY_LOG`: the hypervisor tracks
every page the JVM dirtied during the simulation, and the shim restores them from
the snapshot. A warm JVM dirtying ~400 × 4 KB pages per iteration is typical.

---

## Build steps (from scratch)

```bash
# 1. Build nyx-lite library (Rust)
cd ~/git/nyx-lite && cargo build --release

# 2. Build guest bridge (C + Java)
cd opendst-nyx-host/guest && make

# 3. Build opendst agent + runner (Java)
cd opendst && mvn install -pl opendst-agent,opendst-runner -am -q

# 4. Build shim (Rust, links nyx-lite)
cd opendst-nyx-host && cargo build --release

# 5. Build Docker base image
cd opendst && bash opendst-nyx-host/base-image/build-base-image.sh

# 6. Build app image (per project, Maven plugin)
mvn opendst:build -Dopendst.engine=nyx-lite

# 7. Run
java -jar target/opendst-package/opendst-runner.jar run \
     --engine=nyx-lite --image=<app>-opendst-nyx:<version>
```

Environment requirements:
- `KVM` available (`/dev/kvm`) — nyx-lite uses Firecracker
- `docker` — for image extraction
- `genext2fs` — for rootfs packaging (no sudo needed)
- `NYX_KERNEL` env var or `~/git/nyx-lite/vm_image/vmlinux-6.1.58` present

---

## Simplification opportunities

### 1. Eliminate the initial `Hypercall.snapshot()` in NyxGuestEntry

**Current state**: `NyxGuestEntry` issues `Hypercall.snapshot()` (the `SNAPSHOT`
opcode) to satisfy `boot_to_snapshot()` on the host. This creates a shallow initial
snapshot that is immediately superseded by the deferred `BOOT_SNAPSHOT`. The initial
snapshot is only needed because the shim has to block until shared-memory addresses
are registered, but the addresses aren't known until `SharedMemory` is constructed.

**Simplification**: The `boot_to_snapshot()` host loop could wait for two
`SHAREMEM` exits and then one `BOOT_SNAPSHOT` exit — no intermediate `SNAPSHOT`
needed. The guest would emit `BOOT_SNAPSHOT` directly from `Source.next()`. This
removes one snapshot, one restore, and the shallow snapshot entry from the cache.

### 2. Remove `NyxSegmentHypercall` reflection indirection

**Current state**: `NyxSegmentHypercall` (in `opendst-agent`) reaches
`opendst.nyx.guest.Hypercall` via `Class.forName(..., ClassLoader.getSystemClassLoader())`.
This is needed because the agent runs under the bootstrap classloader and `nyx-guest.jar`
is on the system classpath.

**Simplification**: Move `Hypercall.snapshot()` and `Hypercall.bootSnapshot()` into a
thin interface/class in `opendst-common` (already on the agent classpath), with the
real implementation injected at startup via a static field set by `NyxGuestEntry`. The
agent would call a `Runnable`/functional interface rather than reflecting. Removes two
reflection calls per segment boundary.

### 3. Replace `SharedOutputStream` string scan with a dedicated hypercall

**Current state**: `SharedOutputStream` scans every log line for
`"message":"stopped"` to know when to issue `EXECDONE`. This couples the stop
detection to the JSON wire format.

**Simplification**: `Simulator` already has a lifecycle callback when the simulation
stops (it logs "stopped" explicitly). Add a `NyxSegmentHypercall.requestDone()` call
there directly — same place that would call `markDone()` in any other lifecycle hook.
`SharedOutputStream` becomes a simple pass-through with no scanning logic.

### 4. Replace genext2fs + ext2 with a simpler rootfs approach

**Current state**: `NyxImageManager` exports the Docker image, stages it to disk, and
calls `genext2fs` to build an 800 MB ext2 file. Staging + genext2fs takes 30–60 s on
first use and requires an 800 MB pre-allocated image.

**Simplification**: Use a read-only SquashFS image (`mksquashfs`, available everywhere)
for the base OS files, and a small writable overlay (tmpfs or a small ext4) for runtime
writes. SquashFS compresses the Alpine+JDK layer to ~150 MB and builds in < 5 s.
Alternatively, Firecracker supports `io_engine: "Sync"` with pre-built images; ship
the base image as a pre-built `.ext4` artifact in the Maven plugin resources and only
layer the app deployment on top with a second virtio-blk drive.

### 5. Merge `opendst-nyx-host` into the main opendst repo as a Cargo workspace member

**Current state**: `opendst-nyx-host` is a sibling directory with its own
`Cargo.toml` that references `nyx-lite` via a path dependency. The build requires
manually running `cargo build` before `build-base-image.sh`.

**Simplification**: Add a Maven exec-plugin step to `opendst-runner/pom.xml` (or the
Maven plugin's pom) that runs `cargo build --release` in `opendst-nyx-host` as part
of the normal `mvn install` lifecycle. This eliminates the out-of-band manual step
and ensures the shim is always in sync with the Java code.
