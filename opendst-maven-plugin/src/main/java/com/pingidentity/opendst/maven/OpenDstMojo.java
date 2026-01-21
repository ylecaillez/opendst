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
package com.pingidentity.opendst.maven;

import static com.pingidentity.opendst.maven.Commons.INSTRUMENTED_WARS_DIR;
import static com.pingidentity.opendst.maven.Commons.JAVA_DEBUG_OPTS;
import static com.pingidentity.opendst.maven.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.maven.Commons.WARS_DIR;
import static com.pingidentity.opendst.maven.Commons.deleteRecursively;
import static java.io.File.pathSeparator;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.join;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Collectors.toSet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pingidentity.opendst.Faults;
import com.pingidentity.opendst.Plan;
import com.pingidentity.opendst.maven.Orchestrator.GuidedOrchestrator;
import com.pingidentity.opendst.maven.Orchestrator.ReplayOrchestrator;
import com.pingidentity.opendst.maven.TestDiscoverer.TestRequest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import tools.jackson.databind.JsonNode;

@Mojo(
        name = "test",
        defaultPhase = LifecyclePhase.INTEGRATION_TEST,
        requiresDependencyResolution = ResolutionScope.TEST)
public class OpenDstMojo extends AbstractMojo {
    private static final String OPENDST_SIMULATOR_JAR = "/META-INF/agents/opendst-core.jar";

    record LogStatement(@JsonProperty("it") long iteration, String source, JsonNode log) {}

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "opendst.test")
    private String test;

    @Parameter(property = "opendst.includes")
    private List<String> includes;

    @Parameter(property = "opendst.excludes")
    private List<String> excludes;

    @Parameter(property = "opendst.parallelism", defaultValue = "1")
    private int parallelism;

    @Parameter(property = "opendst.jvmArguments")
    private String jvmArguments;

    @Parameter(property = "opendst.debug")
    private String debug;

    @Parameter(property = "opendst.replayProbability", defaultValue = "0.05")
    private double replayProbability;

    @Parameter(property = "opendst.branchProbability", defaultValue = "0.7")
    private double branchProbability;

    @Parameter(property = "opendst.plan")
    private File planFile;

    @Parameter(property = "opendst.duration", defaultValue = "100000")
    private long duration;

    @Parameter(property = "opendst.stagnation-limit", defaultValue = "100")
    private int stagnationLimit;

    @Parameter
    private NetworkFaults networkFaults;

    @Parameter
    private FileSystemFaults fileSystemFaults;

    @Parameter(property = "opendst.logSpy")
    private File logSpy;

    @Parameter(property = "skipTests")
    private boolean skipTests;

    public static class NetworkFaults {
        @Parameter(defaultValue = "false")
        boolean enabled = false;

        @Parameter(defaultValue = "0.01")
        double latencyProbability = 0.01;

        @Parameter(defaultValue = "10")
        long minLatencyMs = 10;

        @Parameter(defaultValue = "100")
        long maxLatencyMs = 100;

        @Parameter(defaultValue = "0.001")
        double partitionProbability = 0.001;

        @Parameter(defaultValue = "1000")
        long minPartitionDurationMs = 1000;

        @Parameter(defaultValue = "10000")
        long maxPartitionDurationMs = 10000;

        @Parameter(defaultValue = "0.0")
        double packetLossProbability = 0.0;
    }

    public static class FileSystemFaults {
        @Parameter(defaultValue = "false")
        boolean enabled = false;

        @Parameter(defaultValue = "0.005")
        double ioErrorProbability = 0.005;
    }

    private Faults.Config getFaultsConfig() {
        var net = networkFaults != null
                ? new Faults.Config.NetworkConfig(
                        networkFaults.enabled,
                        networkFaults.latencyProbability,
                        networkFaults.minLatencyMs,
                        networkFaults.maxLatencyMs,
                        networkFaults.partitionProbability,
                        networkFaults.minPartitionDurationMs,
                        networkFaults.maxPartitionDurationMs,
                        networkFaults.packetLossProbability)
                : new Faults.Config.NetworkConfig();

        var fs = fileSystemFaults != null
                ? new Faults.Config.FileSystemConfig(fileSystemFaults.enabled, fileSystemFaults.ioErrorProbability)
                : new Faults.Config.FileSystemConfig();

        return new Faults.Config(net, fs);
    }

    private Path opendstBasePath;
    private Path basePath;
    private Path instrumentedWarsDir;
    private Set<Instrumentation.DiscoveredProperty> discoveredProperties;
    private OpenDstLogger logger;

    private boolean isDebug() {
        return debug != null && !"false".equalsIgnoreCase(debug);
    }

    private boolean isReplay() {
        return planFile != null;
    }

    private String debugArgs() {
        if (!isDebug()) {
            return null;
        }
        return debug.isEmpty() || "true".equalsIgnoreCase(debug) ? JAVA_DEBUG_OPTS : debug;
    }

    private String agentJarPath;

    private static String getAgentJarPath() throws MojoFailureException {
        try (var is = OpenDstMojo.class.getResourceAsStream(OPENDST_SIMULATOR_JAR)) {
            if (is == null) {
                throw new MojoFailureException("Could not find embedded opendst-core.jar");
            }
            var agentJar = new File("opendst-core.jar");
            agentJar.deleteOnExit();
            copy(is, agentJar.toPath(), REPLACE_EXISTING);
            return agentJar.getAbsolutePath();
        } catch (Exception e) {
            throw new MojoFailureException("Failed to extract opendst-core.jar", e);
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipTests) {
            getLog().info("Skipping opendst-maven-plugin execution because -DskipTests is enabled.");
            return;
        }
        agentJarPath = getAgentJarPath();
        try {
            basePath = project.getBasedir().toPath().toRealPath();
            opendstBasePath = basePath.resolve("target").resolve("opendst");
        } catch (Exception e) {
            throw new MojoExecutionException(e);
        }
        instrumentedWarsDir = opendstBasePath.resolve(INSTRUMENTED_WARS_DIR);
        try {
            deleteRecursively(basePath, instrumentedWarsDir);
        } catch (IOException e) {
            throw new MojoExecutionException(e);
        }

        logger = new OpenDstLogger(getLog());

        try (var instrumentationExecutor = newFixedThreadPool(getRuntime().availableProcessors() * 2)) {
            discoveredProperties = new Instrumentation(
                            basePath, basePath.resolve(WARS_DIR), instrumentedWarsDir, instrumentationExecutor, logger)
                    .instrumentWars();
        }

        if (isDebug() || isReplay()) {
            this.parallelism = 1;
        }

        var discoverer = new TestDiscoverer(project, basePath, getLog());
        var testsToRun = discoverer.getTestsToRun(test, includes, excludes);
        if (testsToRun.isEmpty()) {
            throw new MojoExecutionException("No OpenDST tests found to run.");
        }

        boolean atLeastOneTestFailed = false;
        try {
            var classpath = getClasspath();
            for (var request : testsToRun) {
                atLeastOneTestFailed |= runSingleTest(request, classpath, testsToRun);
            }
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoFailureException("Failed to resolve runtime classpath", e);
        }

        if (atLeastOneTestFailed) {
            throw new MojoFailureException("OpenDST found at least one failure. See reports for details.");
        }
    }

    /** {@return true if the test failed}. */
    private boolean runSingleTest(TestRequest request, String classpath, List<TestRequest> allTests)
            throws MojoExecutionException, MojoFailureException {
        var testClass = request.className();
        var testMethod = request.methodName();

        var testProperties = discoveredProperties.stream()
                .filter(prop -> prop.origin().equals(testClass)
                        || allTests.stream().noneMatch(t -> t.className().equals(prop.origin())))
                .collect(toSet());

        Orchestrator orchestrator;
        if (isReplay()) {
            getLog().info("OpenDST replays %s#%s with plan %s"
                    .formatted(testClass, testMethod, basePath.relativize(planFile.toPath())));
            try {
                orchestrator = new ReplayOrchestrator(JSON_MAPPER.readValue(planFile, Plan.class));
            } catch (Exception e) {
                throw new MojoFailureException("Failed to read plan file", e);
            }
        } else {
            var faultsConfig = getFaultsConfig();
            getLog().info("OpenDST runs %s#%s with parallelism set to %d (faults: %s)"
                    .formatted(testClass, testMethod, this.parallelism, faultsConfig.summary()));
            orchestrator = new GuidedOrchestrator(logger, duration, branchProbability, faultsConfig);
        }

        var executor = new TestExecutor(
                opendstBasePath.resolve(testClass),
                testClass,
                testMethod,
                classpath,
                testProperties,
                new TestExecutor.JvmConfig(instrumentedWarsDir, agentJarPath, jvmArguments, debugArgs(), logSpy),
                logger,
                orchestrator,
                new TestExecutor.RunConfig(replayProbability, isDebug() || isReplay(), stagnationLimit, parallelism));

        try {
            return executor.execute();
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Interrupted while waiting for test execution", e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private String getClasspath() throws DependencyResolutionRequiredException, MojoFailureException {
        var classpathElements = new ArrayList<>(project.getTestClasspathElements());
        var testClassesDir = basePath.resolve("target/test-classes").toAbsolutePath();
        for (int i = 0; i < classpathElements.size(); i++) {
            if (testClassesDir.equals(Path.of(classpathElements.get(i)).toAbsolutePath())) {
                classpathElements.set(
                        i,
                        instrumentedWarsDir
                                .resolve("test-classes.jar")
                                .toAbsolutePath()
                                .toString());
            }
        }
        classpathElements.remove(
                basePath.resolve("target/classes").toAbsolutePath().toString());
        classpathElements.add(getOpenDstRunnerPath());
        return join(pathSeparator, classpathElements);
    }

    private String getOpenDstRunnerPath() throws MojoFailureException {
        var classFile = "/" + OpenDstRunner.class.getName().replace('.', '/') + ".class";
        try {
            var jar = opendstBasePath.resolve("runner.jar");
            createDirectories(opendstBasePath);
            try (var is = OpenDstRunner.class.getResourceAsStream(classFile);
                    var jos = new JarOutputStream(newOutputStream(jar))) {
                if (is == null) {
                    throw new MojoFailureException("Could not find " + classFile);
                }
                jos.putNextEntry(
                        new JarEntry(OpenDstRunner.class.getPackageName().replace('.', '/') + "/"
                                + OpenDstRunner.class.getSimpleName() + ".class"));
                is.transferTo(jos);
                jos.closeEntry();
                return jar.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            throw new MojoFailureException("Failed to extract runner.jar", e);
        }
    }
}
