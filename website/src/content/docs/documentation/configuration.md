---
title: Maven Configuration Reference
description: All parameters for the OpenDST Maven plugin.
---

The `build` goal is bound to the `package` phase by default. It instruments bytecode, discovers assertions, and produces a self-contained simulation JAR.

Parameters can be set in the plugin `<configuration>` block or via system properties (`-D` on the command line).

---

## Parameters

| Parameter | System Property | Default | Description |
|-----------|----------------|---------|-------------|
| `descriptor` | `opendst.descriptor` | — | **(Required)** Path to the `deployment.yaml` file that describes the simulation topology. |
| `parallelism` | `opendst.parallelism` | `1` | Number of concurrent simulation JVMs. |
| `stagnationLimit` | `opendst.stagnation-limit` | `100` | Stop the session after N consecutive runs with no new signal discoveries. |
| `duration` | `opendst.duration` | `100000` | Total iteration budget per plan. Higher values mean longer, deeper simulations. |
| `branchProbability` | `opendst.branchProbability` | `0.7` | Probability of branching from a known signal vs. starting a fresh random walk. |
| `replayProbability` | `opendst.replayProbability` | `0.05` | Probability of replaying a past plan for determinism verification. |
| `jvmArguments` | `opendst.jvmArguments` | — | Extra JVM arguments passed to child simulation processes. |
| `outputJar` | `opendst.outputJar` | `target/${finalName}-opendst.jar` | Path to the self-contained output JAR. |
| `skip` | `opendst.skip` | `false` | Skip the build goal entirely. |

---

## Example Configuration

```xml title="pom.xml"
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

The `build` goal produces a self-contained executable JAR at `target/<project>-<version>-opendst.jar`. Run it directly:

```bash
# Build the simulation JAR
mvn package

# Run the simulation
java -jar target/my-app-1.0-opendst.jar

# Override parameters at runtime
java -jar target/my-app-1.0-opendst.jar --parallelism 8 --stagnation-limit 500
```
