/*
 * Copyright 2026 Ping Identity Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingidentity.opendst.runner;

import static com.pingidentity.opendst.runner.Assertion.SIMULATION_STARTED;
import static com.pingidentity.opendst.runner.Assertion.SIMULATION_STOPPED_SUCCESSFULLY;
import static com.pingidentity.opendst.runner.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.runner.OpenDstLogger.ofConsole;
import static java.lang.System.exit;
import static java.nio.file.Files.createDirectories;

import com.pingidentity.opendst.Faults;
import com.pingidentity.opendst.Plan;
import com.pingidentity.opendst.runner.Commons.DurationUtils;
import com.pingidentity.opendst.runner.Orchestrator.GuidedOrchestrator;
import com.pingidentity.opendst.runner.Orchestrator.ReplayOrchestrator;
import com.pingidentity.opendst.runner.TestExecutor.JvmConfig;
import com.pingidentity.opendst.runner.TestExecutor.RunConfig;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Orchestrator entry point for the self-contained JAR.
 *
 * <p>Invoked reflectively by {@link Bootstrap} after extraction and classloader setup.
 * The first argument is always the working directory path (prepended by the launcher).
 *
 * <p>The working directory has the following structure:
 * <pre>
 *   &lt;workingDir&gt;/
 *     deployment/     — extracted JAR contents (configs, classes, system JARs, apps)
 *     runs/           — ephemeral per-fork directories (created/deleted each run)
 *     report/         — simulation output (persists across runs)
 *       report.json
 *       plans/        — execution plan files (accumulated)
 * </pre>
 *
 * <p>This class:
 * <ol>
 *   <li>Loads the assertion catalog from {@code deployment/META-INF/opendst/assertions.json}</li>
 *   <li>Loads the build configuration from {@code deployment/build-config.json}</li>
 *   <li>Spawns child JVMs running {@link DeploymentRunner} via {@link TestExecutor}</li>
 *   <li>Uses {@link Orchestrator.GuidedOrchestrator} to drive exploration</li>
 * </ol>
 */
@Command(name = "opendst", description = "Run OpenDST deterministic simulation tests", mixinStandardHelpOptions = true)
public final class BuildRunner implements Callable<Integer> {

    @Parameters(index = "0", hidden = true, description = "Working directory (set by Bootstrap)")
    private Path workingDir;

    /**
     * Controls the exploration stopping strategy.
     *
     * <ul>
     *   <li>{@code EXPLORE} — run until the stagnation limit is reached (default)</li>
     *   <li>{@code VALIDATE} — stop early when all assertions are passing,
     *       after at least {@code stagnation-limit} executions for confidence</li>
     *   <li>{@code VERIFY} — stop immediately on first assertion failure</li>
     * </ul>
     */
    public enum RunMode {
        EXPLORE,
        VALIDATE,
        VERIFY
    }

    @Option(
            names = "--mode",
            description = "Exploration strategy: explore (default), validate, verify",
            defaultValue = "EXPLORE")
    private RunMode mode;

    @Option(
            names = "--fork-count",
            description =
                    "Number of concurrent simulation forks. Supports a 'C' suffix for CPU-relative"
                        + " values (e.g. '1C' = all cores, '0.5C' = half). Plain integers are used"
                        + " as-is. Default: max(1, CPUs/2 - 1)")
    private String forkCountSpec;

    @Option(names = "--duration", description = "Maximum simulation duration in milliseconds", defaultValue = "100000")
    private long duration;

    @Option(
            names = "--branch-probability",
            description = "Probability of branching to explore a new path",
            defaultValue = "0.7")
    private double branchProbability;

    @Option(
            names = "--replay-probability",
            description = "Probability of replaying a previous trace",
            defaultValue = "0.05")
    private double replayProbability;

    @Option(
            names = "--stagnation-limit",
            description = "Stop after this many iterations without new coverage",
            defaultValue = "100")
    private int stagnationLimit;

    @Option(names = "--plan", description = "Replay a saved plan file instead of exploring")
    private Path planFile;

    @Option(names = "--jvm-args", description = "JVM arguments for child processes (overrides build-time default)")
    private String jvmArgs;

    public static void main(String[] args) {
        var cmd = new CommandLine(new BuildRunner());
        cmd.setCaseInsensitiveEnumValuesAllowed(true);
        exit(cmd.execute(args));
    }

    @Override
    public Integer call() throws Exception {
        int forkCount = resolveForkCount(forkCountSpec);

        // Derive directory layout from working directory
        var deploymentDir = workingDir.resolve("deployment");
        var reportDir = workingDir.resolve("report");
        var runsDir = workingDir.resolve("runs");
        createDirectories(reportDir.resolve("plans"));
        createDirectories(runsDir);

        // 1. Load assertions
        var assertionsFile = deploymentDir.resolve("META-INF/opendst/assertions.json");
        Set<Assertion> discoveredAssertions = JSON_MAPPER.readValue(
                assertionsFile.toFile(),
                JSON_MAPPER.getTypeFactory().constructCollectionType(Set.class, Assertion.class));

        // Merge discovered assertions with built-in lifecycle assertions
        var assertions = new LinkedHashSet<>(discoveredAssertions);
        assertions.add(SIMULATION_STARTED);
        assertions.add(SIMULATION_STOPPED_SUCCESSFULLY);

        // 2. Load build config
        var configFile = deploymentDir.resolve("build-config.json");
        var config = JSON_MAPPER.readValue(configFile.toFile(), BuildConfig.class);

        // 3. Build classpath for child JVMs
        var classpath = buildChildClasspath(deploymentDir);

        // 4. Set up orchestrator and run
        var logger = ofConsole();
        var faultsConfig = toFaultsConfig(config.faults());

        var instrumentedAppsDir = deploymentDir.resolve("apps");
        var agentJarPath = deploymentDir
                .resolve("system/opendst-agent.jar")
                .toAbsolutePath()
                .toString();

        // Merge JVM arguments: CLI --jvm-args wins, else fall back to build-time default
        var effectiveJvmArgs = jvmArgs != null ? jvmArgs : config.jvmArguments();

        var jvmConfig = new JvmConfig(
                instrumentedAppsDir, agentJarPath, effectiveJvmArgs, null, null, DeploymentRunner.class.getName());

        // Replay mode: load a saved plan and execute it once
        boolean isReplay = planFile != null;
        Orchestrator orchestrator;
        if (isReplay) {
            var plan = JSON_MAPPER.readValue(planFile.toFile(), Plan.class);
            orchestrator = new ReplayOrchestrator(plan);
            forkCount = 1;
        } else {
            orchestrator = new GuidedOrchestrator(logger, duration, branchProbability, faultsConfig);
        }

        var runConfig = new RunConfig(isReplay ? 0 : replayProbability, isReplay, stagnationLimit, forkCount, mode);

        // TestExecutor uses testClass/testMethod as child JVM args.
        // For DeploymentRunner, we pass the deployment dir path so the child knows where to find deployment.yaml.
        var reportGenerator = new ReportGenerator(assertions);
        new TestExecutor(
                        reportDir,
                        runsDir,
                        deploymentDir.toAbsolutePath().toString(), // passed as "testClass" arg to child JVM
                        "run", // passed as "testMethod" arg (unused but required)
                        classpath,
                        jvmConfig,
                        logger,
                        orchestrator,
                        runConfig)
                .execute(reportGenerator);

        if (isReplay) {
            logger.raw().info("Replay complete.");
            return 0;
        }

        // Always write a final report — even if no run was "interesting", we still
        // want a summary of whatever happened during the simulation.
        var reportFile = reportDir.resolve("report.json");
        reportGenerator.generate(reportFile);

        logger.raw().info("Simulation complete. Report written to: " + reportFile);

        if (reportGenerator.hasFailures()) {
            return 1;
        }
        return 0;
    }

    /**
     * Resolves the {@code --fork-count} specification into a concrete thread count.
     *
     * <p>Accepts three forms:
     * <ul>
     *   <li>{@code null} or empty — default: {@code max(1, availableProcessors/2 - 1)}</li>
     *   <li>A plain integer (e.g. {@code "4"}) — used as-is, clamped to min 1</li>
     *   <li>A CPU-relative value with {@code C} suffix (e.g. {@code "0.5C"}) —
     *       {@code max(1, floor(multiplier * availableProcessors))}</li>
     * </ul>
     */
    private static int resolveForkCount(String spec) {
        int cores = Runtime.getRuntime().availableProcessors();
        if (spec == null || spec.isEmpty()) {
            return Math.max(1, cores / 2 - 1);
        }
        if (spec.endsWith("C") || spec.endsWith("c")) {
            double multiplier = Double.parseDouble(spec.substring(0, spec.length() - 1));
            return Math.max(1, (int) (multiplier * cores));
        }
        return Math.max(1, Integer.parseInt(spec));
    }

    /**
     * Builds the classpath for child JVMs. Includes the deployment root (plugin + opendst
     * classes) and all library JARs from {@code deployment/system/}.
     */
    private static String buildChildClasspath(Path deploymentDir) {
        var sb = new StringBuilder();
        sb.append(deploymentDir.toAbsolutePath());

        // Add all JARs from system/ (Jackson, SnakeYAML, opendst-agent)
        var systemDir = deploymentDir.resolve("system").toFile();
        var jars = systemDir.listFiles((_, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (var jar : jars) {
                sb.append(File.pathSeparatorChar).append(jar.getAbsolutePath());
            }
        }
        return sb.toString();
    }

    private static Faults.Config toFaultsConfig(BuildConfig.FaultsConfig faults) {

        if (faults == null) {
            return new Faults.Config();
        }
        var net = faults.network() != null && faults.network().enabled()
                ? new Faults.Config.NetworkConfig(
                        true,
                        DurationUtils.parse(faults.network().latencyMinimum()),
                        DurationUtils.parse(faults.network().latencyFast()),
                        DurationUtils.parse(faults.network().latencySlow()),
                        faults.network().cloggingProbability(),
                        DurationUtils.parse(faults.network().cloggingLatencyMaximum()),
                        0.001,
                        0.001)
                : new Faults.Config.NetworkConfig();
        return new Faults.Config(net);
    }

    /** Build-time configuration baked into the self-contained JAR. */
    public record BuildConfig(String jvmArguments, FaultsConfig faults) {

        /** Serializable faults configuration using string-based durations. */
        public record FaultsConfig(NetworkFaultsConfig network) {}

        public record NetworkFaultsConfig(
                boolean enabled,
                String latencyMinimum,
                String latencyFast,
                String latencySlow,
                double cloggingProbability,
                String cloggingLatencyMaximum) {}
    }
}
