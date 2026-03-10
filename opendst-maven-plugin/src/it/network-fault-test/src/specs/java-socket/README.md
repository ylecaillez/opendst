# Java Socket API Formal Specification (P Language)

A formal specification of the Java SE 17 Socket and ServerSocket API modeled in the [P language](https://github.com/p-org/P), a state-machine-based formal modeling language for distributed systems.

## Goal

Model the interaction between two sockets (client and server) via a network medium. The spec is based exclusively on the **Java SE 17 Javadoc** — NOT from any implementation code.

**Critical principle**: Model ONLY what the Javadoc explicitly guarantees. Where the Javadoc says only `Throws: IOException - if an I/O error occurs`, the exception is modeled as **nondeterministic** (using P's `$` operator) rather than inferring specific preconditions from implementation behavior.

## Files

| File | Description | ~Lines |
|---|---|---|
| `JavaSocketTypes.p` | Type definitions, enums (`tSocketAddress`, `tConnectStatus`, `tIOResult`, `tSocketRole`), and all event declarations. Field names use `dat` not `data`. | ~156 |
| `Network.p` | Network broker + NetworkChannel machines. Models TCP connection establishment, FIFO byte delivery, EOF propagation, and connection reset. | ~221 |
| `ServerSocket.p` | ServerSocketMachine. States: Unbound -> Listening -> Closed. Nondeterministic IOException on accept/bind/close where Javadoc is generic. | ~192 |
| `Socket.p` | SocketMachine. States: Init -> Unconnected/WaitForChannel -> Connected -> Closed. Flags for outputShutdown, inputShutdown, peerEOF, peerReset. Nondeterministic IOException where Javadoc is generic. | ~400 |
| `Specifications.p` | 4 safety spec monitors + 2 liveness spec monitors (see below). | ~427 |
| `TestDriver.p` | 7 test driver machines: BasicClientServer, ConnectionRefused, WriteAfterClose, WriteAfterShutdownOutput, AcceptOnClosedServer, ReadAfterPeerShutdown, HalfClose. | ~719 |
| `Modules.p` | Module and test case declarations. Liveness specs asserted on relevant test cases. | ~65 |

## Build & Check

### Prerequisites

- [P compiler](https://github.com/p-org/P) installed (e.g. via `dotnet tool install -g P`)
- .NET 8.0 SDK

### Compile

```bash
p compile \
  -pf specs/java-socket/JavaSocketTypes.p \
      specs/java-socket/Network.p \
      specs/java-socket/ServerSocket.p \
      specs/java-socket/Socket.p \
      specs/java-socket/Specifications.p \
      specs/java-socket/TestDriver.p \
      specs/java-socket/Modules.p \
  -pn JavaSocket \
  -o specs/java-socket/output
```

### Run all tests

```bash
for tc in tcConnectionRefused tcAcceptOnClosedServer tcWriteAfterClose \
          tcWriteAfterShutdownOutput tcReadAfterPeerShutdown \
          tcBasicClientServer tcHalfClose; do
  p check specs/java-socket/output/CSharp/net8.0/JavaSocket.dll \
    -tc $tc -s 1000 -t 60
done
```

### Verification Results (all PASS — safety + liveness)

| Test Case | Specs Asserted | Schedules | Timelines |
|---|---|---|---|
| tcConnectionRefused | Safety only | 1000 | 1 |
| tcAcceptOnClosedServer | Safety only | 1000 | 2 |
| tcWriteAfterClose | Safety only | 1000 | 9 |
| tcWriteAfterShutdownOutput | Safety only | 1000 | 11 |
| tcReadAfterPeerShutdown | Safety + EOFLiveness | 1000 | 6 |
| tcBasicClientServer | Safety + DeliveryLiveness + EOFLiveness | 1000 | 244 |
| tcHalfClose | Safety + DeliveryLiveness + EOFLiveness | 1000 | 160 |

---

## Specifications

### Safety Specs

4 safety spec monitors that catch violations immediately:

| Monitor | Property |
|---|---|
| **DataIntegrity** | Data read by a socket is a prefix of data written by the peer. No phantom data, no reordering. |
| **NoWriteAfterClose** | A closed socket must never successfully write data. |
| **NoPhantomData** | Data can only be read from sockets that are part of an established connection. |
| **IOExceptionOnClosedSocket** | No successful read or write can occur on a closed socket. |

### Liveness Specs

2 liveness spec monitors using P's hot/cold state mechanism. The model checker flags a **liveness violation** if an execution ends in a `hot` state (i.e., an obligation was never fulfilled or excused).

| Monitor | Property | Observed Events |
|---|---|---|
| **DeliveryLiveness** | Data written by a socket must eventually be read by the peer, unless the connection is disrupted. | `eSpec_ConnectionEstablished`, `eSpec_DataWritten`, `eSpec_DataRead`, `eSpec_SocketClosed`, `eSpec_ConnectionReset`, `eSpec_IOExceptionRaised` |
| **EOFLiveness** | If `shutdownOutput()` succeeds, the peer must eventually read EOF, unless the connection is disrupted. | `eSpec_ConnectionEstablished`, `eSpec_ShutdownOutputCompleted`, `eSpec_EOFRead`, `eSpec_SocketClosed`, `eSpec_ConnectionReset`, `eSpec_IOExceptionRaised` |

**How liveness works**: Each monitor tracks obligations (pending byte deliveries or pending EOF deliveries). When an obligation exists and hasn't been excused, the monitor enters a `hot` state. When all obligations are fulfilled or excused, it transitions to a `cold` state. The model checker reports any execution that terminates in a `hot` state as a bug.

**Excuse conditions**: A delivery obligation is excused (cleared without fulfillment) when any of the following occurs:
- The socket or its peer is closed (`eSpec_SocketClosed`)
- The connection is reset (`eSpec_ConnectionReset`)
- A `close()` attempt throws IOException (`eSpec_IOExceptionRaised` with `operation == "close"`) — the socket is left in an undefined state, so delivery cannot be expected

**Spec events added for liveness**:
- `eSpec_ShutdownOutputCompleted: (socket: machine)` — announced when `shutdownOutput()` succeeds
- `eSpec_EOFRead: (socket: machine)` — announced when a read returns `IO_EOF`
- `eSpec_ConnectionReset: (socket: machine)` — announced when a socket receives a reset notification

**Bugs found during development**: The liveness specs surfaced real issues in test drivers where nondeterministic failure paths (e.g., `shutdownOutput()` failing) caused tests to abandon sockets without proper cleanup. This left delivery obligations unfulfilled. The fix was to route abandoned paths through graceful close logic so that sockets are properly closed (sending resets and excusing delivery).

---

## Design Decisions

### Nondeterministic IOException Modeling

The central design principle: if the Java SE 17 Javadoc does NOT explicitly document a specific precondition for `IOException`, we model the exception as a nondeterministic choice (`$` in P). The model checker then explores both the success and failure paths.

This means some operations can "succeed" in states where a real JVM would always throw (e.g., calling a method on a closed socket when the Javadoc only says generic "if an I/O error occurs"). This is a **safe overapproximation** — the success paths in these cases are "vacuous" (no state transitions or side effects), so no false negatives are introduced. Tests handle this by checking responses and abandoning paths on nondeterministic failure.

### Deterministic vs. Nondeterministic — Full Breakdown

**Deterministic (Javadoc explicitly documents the behavior):**

| Scenario | Behavior | Javadoc Source |
|---|---|---|
| `write()` after `close()` | IOException | `close()` doc: "not available for further networking use" |
| `write()` after `shutdownOutput()` | IOException | `shutdownOutput()` doc: "the stream will throw an IOException" |
| `read()` after `close()` | IOException | `getInputStream()` doc: throws if "the socket is closed" |
| `read()` after `shutdownInput()` | Returns -1 (EOF) | `shutdownInput()` doc: "read methods will return -1" |
| `read()` after peer `shutdownOutput()` | EOF | Implied by TCP + `shutdownOutput()` doc: "TCP's normal connection termination sequence" |
| `read()` on broken connection | IOException | `getInputStream()` doc: "all subsequent calls to read will throw an IOException" |
| `connect()` on closed socket | IOException | `close()` doc: "can't be reconnected or rebound" |
| `close()` on already-closed | No-op | `Closeable` contract |
| `close()` interrupting blocked `accept()` | SocketException | `ServerSocket.close()` doc |
| `bind()` on already-bound ServerSocket | IOException | `ServerSocket.bind()` doc: "or if the socket is already bound" |

**Nondeterministic (Javadoc only says generic "IOException — if an I/O error occurs"):**

| Method | Scenario |
|---|---|
| `shutdownOutput()` | Generic IOException |
| `shutdownInput()` | Generic IOException |
| `close()` | Success/failure |
| `connect()` | On already-connected socket |
| `write()` on broken connection | `getOutputStream()` and `OutputStream.write()` say nothing about broken connections (unlike `getInputStream()` which explicitly documents read behavior) |
| `accept()` | On unbound or closed ServerSocket |
| `bind()` | On closed ServerSocket |

---

## P Language Notes & Pitfalls

These notes capture practical lessons learned while building this specification.

### Core Constructs

- State machines communicate via **async events**: `send target, event, payload;` is asynchronous.
- `raise event, payload;` handles the event immediately within the current machine.
- `announce event, payload;` publishes to spec monitors (observer machines).
- `$` is nondeterministic boolean choice — the model checker explores both `true` and `false` branches.
- `spec M observes e1, e2 { ... }` — observer machines that **cannot** `send`, `new`, or `receive`. They **must handle or ignore ALL observed events** or the checker throws "received event that cannot be handled."

### Liveness (Hot/Cold States)

- **`hot` state** annotation on spec monitor states: the P model checker flags a liveness violation if an execution **ends** in a hot state.
- **`cold` states** (default) are fine to end in.
- Pattern: track obligations (e.g., pending deliveries), go to `hot` state when obligations exist, transition to `cold` when all obligations are fulfilled or excused.

### Naming & Syntax Pitfalls

- **`data` is a reserved keyword** — cannot be used as a field name or variable. We renamed all `data` fields/variables to `dat`.
- **Variables cannot be declared at the state level** — only at machine level (`machine { var x: int; ... }`) or inside function/entry/handler bodies as locals.
- **Variables must be declared before any statements** in a function/entry/handler body.

### Module & Test Syntax

- **Module union syntax**: `(union ModA, ModB, ModC)` with parentheses, NOT `union { ModA, ModB, ModC }` with braces. Braces are only for primitive modules listing machines: `{ Machine1, Machine2 }`.
- **Test case assert syntax**: Use a single `assert Spec1, Spec2, Spec3 in modExpr;` — NOT multiple `assert X in assert Y in`.
- **Spec monitors should NOT be in regular modules** — they are attached via `assert ... in` in test case definitions.

---

## Possible Future Work

None of the following has been requested or implemented. Listed here for reference.

- **More test scenarios** — concurrent read/write, multiple clients, server socket reuse
- **Model socket options** — `SO_TIMEOUT`, `SO_LINGER`, etc.
- **Model `bind()` on client Socket**
