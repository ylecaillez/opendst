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
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.nio.file.Files.createDirectories;

import com.pingidentity.opendst.Faults;
import com.pingidentity.opendst.runner.Commons.DurationUtils;
import com.pingidentity.opendst.runner.Orchestrator.GuidedOrchestrator;
import com.pingidentity.opendst.runner.TestExecutor.JvmConfig;
import com.pingidentity.opendst.runner.TestExecutor.RunConfig;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Orchestrator entry point for the self-contained JAR.
 *
 * <p>Invoked reflectively by {@link Bootstrap} after extraction and classloader setup.
 * The first argument is always the extraction directory path (prepended by the launcher).
 *
 * <p>This class:
 * <ol>
 *   <li>Loads the assertion catalog from {@code META-INF/opendst/assertions.json}</li>
 *   <li>Loads the build configuration from {@code build-config.json}</li>
 *   <li>Spawns child JVMs running {@link DeploymentRunner} via {@link TestExecutor}</li>
 *   <li>Uses {@link Orchestrator.GuidedOrchestrator} to drive exploration</li>
 * </ol>
 */
public final class BuildRunner {
    public static void main(String[] args) {
        try {
            // First argument is always the extraction directory (prepended by Bootstrap)
            var extractDir = Path.of(args[0]);
            var remainingArgs = new String[args.length - 1];
            System.arraycopy(args, 1, remainingArgs, 0, remainingArgs.length);

            // Parse command-line arguments
            var reportOutputDir = parseReportDir(remainingArgs);
            var failFast = parseFlag(remainingArgs, "--fail-fast");

            // 1. Load assertions
            var assertionsFile = extractDir.resolve("META-INF/opendst/assertions.json");
            Set<Assertion> discoveredAssertions = JSON_MAPPER.readValue(
                    assertionsFile.toFile(),
                    JSON_MAPPER.getTypeFactory().constructCollectionType(Set.class, Assertion.class));

            // Merge discovered assertions with built-in lifecycle assertions
            var assertions = new LinkedHashSet<>(discoveredAssertions);
            assertions.add(SIMULATION_STARTED);
            assertions.add(SIMULATION_STOPPED_SUCCESSFULLY);

            // 2. Load build config
            var configFile = extractDir.resolve("build-config.json");
            var config = JSON_MAPPER.readValue(configFile.toFile(), BuildConfig.class);

            // 3. Build classpath for child JVMs
            var classpath = buildChildClasspath(extractDir);

            // 4. Set up orchestrator and run
            var logger = ofConsole();
            var faultsConfig = toFaultsConfig(config.faults());
            var orchestrator = new GuidedOrchestrator(logger, config.duration(), config.branchProbability(), faultsConfig);

            var instrumentedWarsDir = extractDir.resolve("apps");
            var agentJarPath = extractDir.resolve("system/opendst-agent.jar").toAbsolutePath().toString();

            var jvmConfig = new JvmConfig(
                    instrumentedWarsDir, agentJarPath, config.jvmArguments(), null, null,
                    DeploymentRunner.class.getName());
            var runConfig = new RunConfig(
                    config.replayProbability(), false, config.stagnationLimit(), config.parallelism(), failFast);

            // Use user-specified report dir or fall back to temp dir
            var reportBasePath = reportOutputDir != null ? reportOutputDir : extractDir.resolve("report");
            createDirectories(reportBasePath);

            // TestExecutor uses testClass/testMethod as child JVM args.
            // For DeploymentRunner, we pass the extraction dir path so the child knows where to find deployment.yaml.
            var reportGenerator = new ReportGenerator(assertions);
            new TestExecutor(
                    reportBasePath,
                    extractDir.toAbsolutePath().toString(), // passed as "testClass" arg to child JVM
                    "run",                                  // passed as "testMethod" arg (unused but required)
                    classpath,
                    jvmConfig,
                    logger,
                    orchestrator,
                    runConfig)
                    .execute(reportGenerator);

            // Always write a final report — even if no run was "interesting", we still
            // want a summary of whatever happened during the simulation.
            var reportFile = reportBasePath.resolve("report.json");
            reportGenerator.generate(reportFile);

            logger.raw().info("Simulation complete. Report written to: " + reportFile);

            if (failFast && reportGenerator.hasFailures()) {
                exit(1);
            }

        } catch (Exception e) {
            err.println("OpenDST build runner failed:");
            e.printStackTrace(err);
            exit(1);
        }
    }

    /** Parses the {@code --report-dir <path>} argument from the command line. */
    private static Path parseReportDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--report-dir".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        return null;
    }

    /** Returns {@code true} if the given flag is present in the argument array. */
    private static boolean parseFlag(String[] args, String flag) {
        for (var arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the classpath for child JVMs. Includes the extraction root (plugin + opendst
     * classes) and all library JARs from {@code system/}.
     */
    private static String buildChildClasspath(Path extractDir) {
        var sb = new StringBuilder();
        sb.append(extractDir.toAbsolutePath());

        // Add all JARs from system/ (Jackson, SnakeYAML, opendst-agent)
        var systemDir = extractDir.resolve("system").toFile();
        var jars = systemDir.listFiles((_, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (var jar : jars) {
                sb.append(java.io.File.pathSeparatorChar).append(jar.getAbsolutePath());
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
                        DurationUtils.parse(faults.network().cloggingLatencyMaximum()))
                : new Faults.Config.NetworkConfig();
        var fs = faults.fileSystem() != null && faults.fileSystem().enabled()
                ? new Faults.Config.FileSystemConfig(true, faults.fileSystem().ioErrorProbability())
                : new Faults.Config.FileSystemConfig();
        return new Faults.Config(net, fs);
    }

    /** Orchestration configuration baked into the self-contained JAR. */
    public record BuildConfig(long duration, double branchProbability, double replayProbability,
                               int stagnationLimit, int parallelism, String jvmArguments,
                               FaultsConfig faults) {

        /** Serializable faults configuration using string-based durations. */
        public record FaultsConfig(NetworkFaultsConfig network, FileSystemFaultsConfig fileSystem) {}

        public record NetworkFaultsConfig(
                boolean enabled,
                String latencyMinimum,
                String latencyFast,
                String latencySlow,
                double cloggingProbability,
                String cloggingLatencyMaximum) {}

        public record FileSystemFaultsConfig(boolean enabled, double ioErrorProbability) {}
    }
}
