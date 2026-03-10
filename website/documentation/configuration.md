---
title: Configuration Reference
description: Build-time and runtime parameters for the OpenDST Maven plugin and simulation JAR.
---

# Configuration Reference

The `build` goal is bound to the `package` phase by default. Its only job is to instrument bytecode, discover assertions, and produce a self-contained simulation JAR. All runtime parameters are baked into the JAR as defaults and used when the JAR is executed.

---

## Build Parameters

These parameters control the Maven plugin itself. They can be set in the plugin `<configuration>` block or via system properties (`-D` on the command line).

| Parameter | System Property | Default | Description |
|-----------|----------------|---------|-------------|
| `descriptor` | `opendst.descriptor` | — | **(Required)** Path to the `deployment.yaml` file that describes the simulation topology. |
| `outputJar` | `opendst.outputJar` | `target/${finalName}-opendst.jar` | Path to the self-contained output JAR. |
| `skip` | `opendst.skip` | `false` | Skip the build goal entirely. |

---

## Runtime Defaults

These parameters control the simulation session. They are set at build time in the plugin `<configuration>` block and baked into the JAR as defaults (in `build-config.json`). They are used by the runner when the JAR is executed with `java -jar`.

| Parameter | System Property | Default | Description |
|-----------|----------------|---------|-------------|
| `duration` | `opendst.duration` | `100000` | Total iteration budget per plan. Higher values mean longer, deeper simulations. |
| `branchProbability` | `opendst.branchProbability` | `0.7` | Probability of branching from a known signal vs. starting a fresh random walk. |
| `replayProbability` | `opendst.replayProbability` | `0.05` | Probability of replaying a past plan for determinism verification. |
| `stagnationLimit` | `opendst.stagnation-limit` | `100` | Stop the session after N consecutive runs with no new signal discoveries. |
| `parallelism` | `opendst.parallelism` | `1` | Number of concurrent simulation JVMs. |
| `jvmArguments` | `opendst.jvmArguments` | — | Extra JVM arguments passed to child simulation processes. |

---

## Example Configuration

```xml
<plugin>
    <groupId>com.pingidentity.opendst</groupId>
    <artifactId>opendst-maven-plugin</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <executions>
        <execution>
            <goals><goal>build</goal></goals>
            <configuration>
                <descriptor>${project.basedir}/deployment.yaml</descriptor>
                <parallelism>8</parallelism>
                <stagnationLimit>200</stagnationLimit>
                <replayProbability>0.05</replayProbability>
                <jvmArguments>-XX:TieredStopAtLevel=1 -Xms64m -Xmx64m</jvmArguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## Running the Simulation

The `build` goal produces a self-contained executable JAR at `target/<project>-<version>-opendst.jar`. The JAR contains everything needed to run: the orchestrator, the simulator agent, your instrumented applications and their dependencies, the deployment descriptor, and the baked-in configuration. Run it directly:

```bash
# Build the simulation JAR
mvn package

# Run the simulation
java -jar target/my-app-1.0-opendst.jar

# Write the report to a specific directory
java -jar target/my-app-1.0-opendst.jar --report-dir ./reports

# Exit immediately on first failure
java -jar target/my-app-1.0-opendst.jar --fail-fast
```
