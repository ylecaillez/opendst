# Proposed Critical Path Assertions for OpenDST

These assertions are intended to detect programmer errors and non-deterministic leaks in the core simulation engine.

## Network.java
```java
void registerDns(String hostName, Node host) {
    var lowerCaseHostName = hostName.toLowerCase(ROOT);
    // Ensure we don't accidentally overwrite a node registration
    assert !nameToNode.containsKey(lowerCaseHostName) || nameToNode.get(lowerCaseHostName) == host;
    // ...
}

SocketImpl route(InetAddress from, InetAddress addr, int port) {
    assert from != null;
    assert addr != null;
    // Ensure both IPs are known to the DNS registry (non-deterministic leak detection)
    assert addressToName.containsKey(from) || from.isLoopbackAddress();
    assert addressToName.containsKey(addr) || addr.isLoopbackAddress();
    // ...
}
```

## Node.java
```java
public void attachThread(Thread thread) {
    assert thread != null;
    assert thread.isVirtual();
    // Ensure the thread is not already attached (avoiding duplicate scheduling)
    assert !virtualThreads.contains(thread);
    // ...
}

void startNode(Callable<Void> scenario) {
    assert scenario != null;
    assert !stopped; // Cannot restart a stopped node
    // ...
}
```

## Time.java (Scheduler)
```java
Future<?> scheduleExactlyAt(Node node, Runnable task, Instant at) {
    assert node != null;
    assert task != null;
    assert at != null;
    // Strict monotonicity check
    assert !at.isBefore(now);
    // ...
}
```

## Randomness.java (Source)
```java
protected int next(int bits) {
    assert bits > 0 && bits <= 32;
    // Ensure we haven't exhausted the plan segments unexpectedly
    assert !segments.isEmpty() || iteration < nextIteration;
    // ...
}
```
