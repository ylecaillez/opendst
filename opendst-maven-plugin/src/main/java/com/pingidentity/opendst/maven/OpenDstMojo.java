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

import static java.io.File.pathSeparator;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.join;
import static java.lang.System.exit;
import static java.lang.System.getProperty;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.delete;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.walkFileTree;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Arrays.asList;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static tools.jackson.core.StreamReadFeature.AUTO_CLOSE_SOURCE;
import static tools.jackson.core.StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pingidentity.opendst.maven.Orchestrator.Plan;
import com.pingidentity.opendst.maven.Orchestrator.RandomOrchestrator;
import com.pingidentity.opendst.maven.Orchestrator.ReplayOrchestrator;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

@Mojo(name = "test", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.TEST)
public class OpenDstMojo extends AbstractMojo {
    private static final Path JAVA_BIN_PATH = Path.of(System.getProperty("java.home"), "bin", "java");
    public static final List<String> JAVA_BASE_OPTIONS = List.of(
            "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED", "-Dnet.bytebuddy.safe=true",
            "-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2");
    private static final String JAVA_DEBUG_OPTS =
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005";
    private static final String RUNS_BASE_DIR = "runs";
    private static final String INSTRUMENTED_WARS_DIR = "instrumented-wars";
    private static final String WARS_DIR = "target/wars";
    private static final String FAILURE_BASE_DIR = "failures";
    private static final String RUN_DIR_FORMAT = "run-%d";
    private Orchestrator orchestrator;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "opendst.testClass", required = true)
    private String testClass;

    @Parameter(property = "opendst.testMethod", required = true)
    private String testMethod;

    @Parameter(property = "opendst.parallelism", defaultValue = "1")
    private int parallelism;

    @Parameter(property = "opendst.jvmArguments")
    private String jvmArguments;
    /**
     * Arguments for the JVM debug agent (e.g. -agentlib:jdwp=...).
     * If set to "true" or empty, uses default settings.
     * If set to a string, uses that string as the arguments.
     */
    @Parameter(property = "opendst.debug")
    private String debug;

    @Parameter(property = "opendst.replayProbability", defaultValue = "0.05")
    private double replayProbability;

    @Parameter(property = "opendst.plan")
    private File planFile;

    @Parameter(property = "opendst.duration", defaultValue = "100000")
    private long duration;

    @Parameter(property = "skipTests")
    private boolean skipTests;

    private Path opendstBasePath;
    private Path basePath;
    private Path instrumentedWarsDir;

    private boolean isDebug() {
        return debug != null && !"false".equalsIgnoreCase(debug);
    }

    private boolean isReplay() {
        return planFile != null;
    }

    private String debugArgs() {
        return isDebug() ? debug.isEmpty() || "true".equalsIgnoreCase(debug) ? JAVA_DEBUG_OPTS : debug : null;
    }

    private String agentJarPath;

    private static String getAgentJarPath() throws MojoFailureException {
        try (var is = OpenDstMojo.class.getResourceAsStream("/META-INF/agents/opendst-simulator.jar")) {
            if (is == null) {
                throw new MojoFailureException("Could not find embedded opendst-simulator.jar");
            }
            var agentJar = new File("opendst-simulator.jar");
            agentJar.deleteOnExit();
            copy(is, agentJar.toPath(), REPLACE_EXISTING);
            return agentJar.getAbsolutePath();
        } catch (IOException e) {
            throw new MojoFailureException("Failed to extract opendst-simulator.jar", e);
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipTests) {
            getLog().info("Skipping opendst-maven-plugin execution because -DskipTests is enabled.");
            return;
        }
        try {
            basePath = project.getBasedir().toPath().toRealPath();
            opendstBasePath = basePath.resolve("target").resolve("opendst");
        } catch (IOException e) {
            throw new MojoExecutionException(e);
        }
        agentJarPath = getAgentJarPath();
        var warsDir = project.getBasedir().toPath().resolve(WARS_DIR);
        instrumentedWarsDir = opendstBasePath.resolve(INSTRUMENTED_WARS_DIR);
        try {
            deleteDir(instrumentedWarsDir);
        } catch (IOException e) {
            throw new MojoFailureException(e);
        }
        new Instrumentation(basePath, warsDir, instrumentedWarsDir, getLog()).instrumentWars();

        // Allow command line -Dopendst.parallelism to override pom.xml configuration
        // unless debug is enabled, which forces it to 1
        var parallelism = isDebug() ? "1" : getProperty("opendst.parallelism");
        if (parallelism != null) {
            try {
                this.parallelism = Integer.parseInt(parallelism);
            } catch (NumberFormatException e) {
                getLog().warn("Invalid value for opendst.parallelism: " + parallelism);
            }
        }

        if (isReplay()) {
            getLog().info("OpenDST replays %s#%s with plan %s"
                    .formatted(testClass, testMethod, basePath.relativize(planFile.toPath())));
            orchestrator = new ReplayOrchestrator(getLog(), new ObjectMapper().readValue(planFile, Plan.class));
        } else {
            getLog().info("OpenDST runs %s#%s with parallelism set to %d"
                    .formatted(testClass, testMethod, this.parallelism));
            orchestrator = new RandomOrchestrator(
                    getLog(), opendstBasePath.resolve(FAILURE_BASE_DIR), duration, replayProbability);
        }
        try (var executor = newFixedThreadPool(this.parallelism)) {
            var classpath = getClasspath();
            var simulators = new ArrayList<Future<Void>>();
            for (int i = 0; i < this.parallelism; i++) {
                int count = i;
                simulators.add(executor.submit(() -> runLoop(count, classpath)));
            }
            for (var simulatorExecutor : simulators) {
                simulatorExecutor.get();
            }
            executor.shutdown();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoFailureException("Failed to resolve runtime classpath", e);
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Interrupted while waiting for test execution", e);
        } catch (ExecutionException e) {
            throw new MojoExecutionException("A test execution has failed unexpectedly", e);
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
        classpathElements.add(getMethodRunnerPath());
        return join(pathSeparator, classpathElements);
    }

    private String getMethodRunnerPath() throws MojoFailureException {
        var classFile = "/" + MethodRunner.class.getName().replace('.', '/') + ".class";
        try {
            var dir = basePath.resolve("target").resolve("opendst");
            var jar = dir.resolve("runner.jar");
            createDirectories(dir);
            try (var is = MethodRunner.class.getResourceAsStream(classFile);
                    var jos = new JarOutputStream(newOutputStream(jar))) {
                if (is == null) {
                    throw new MojoFailureException("Could not find " + classFile);
                }
                jos.putNextEntry(
                        new JarEntry(MethodRunner.class.getPackageName().replace('.', '/') + "/"
                                + MethodRunner.class.getSimpleName() + ".class"));
                is.transferTo(jos);
                jos.closeEntry();
                return jar.toAbsolutePath().toString();
            }
        } catch (IOException e) {
            throw new MojoFailureException("Failed to extract opendst-simulator.jar", e);
        }
    }

    private Void runLoop(int count, String classpath) throws InterruptedException, IOException {
        do {
            runOnce(createRunBaseDir(count), classpath, orchestrator.nextPlan());
            // In debug/replay, we just run once
        } while (!isDebug() && !isReplay());
        return null;
    }

    public record LogStatement(long lid, @JsonProperty("it") long iteration, String vhost, JsonNode log) {}

    private void runOnce(Path runBaseDir, String classpath, Plan plan) throws IOException {
        var commandLine = buildJvmCommandLine(runBaseDir, classpath);
        int code = -1;
        var proc = new ProcessBuilder(commandLine)
                .directory(runBaseDir.toFile())
                .redirectErrorStream(true)
                .start();
        var simulatorKiller = new Thread(proc::destroyForcibly);
        getRuntime().addShutdownHook(simulatorKiller);

        // Send the execution plan to the simulator and then listen for log produced.
        var lastLog = new LogStatement(-1, -1, "<none>", NullNode.getInstance());
        new ObjectMapper().writeValue(proc.outputWriter(), plan);
        var mapper = JsonMapper.builder()
                .enable(INCLUDE_SOURCE_IN_LOCATION)
                .disable(FAIL_ON_TRAILING_TOKENS)
                .disable(AUTO_CLOSE_SOURCE)
                .build();
        try (var b = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            for (var line = b.readLine(); line != null; line = b.readLine()) {
                if (line.length() > 2 && line.charAt(0) == '{' && line.charAt(line.length() - 1) == '}') {
                    lastLog = mapper.readValue(line, LogStatement.class);
                    orchestrator.onLogReceived(plan, lastLog);
                } else {
                    getLog().debug("Skipped malformed output: " + line);
                }
            }
            code = proc.waitFor();
        } catch (Exception e) {
            getLog().error("Unable to start the simulator process", e);
        } finally {
            try {
                proc.destroyForcibly();
                getRuntime().removeShutdownHook(simulatorKiller);
            } catch (IllegalStateException e) {
                // Shutdown sequence already started, we cannot mutate shutdown hooks anymore.
                // This is not a problem: killing a stopped process is a no-op.
            } finally {
                orchestrator.onPlanTerminated(plan, code, lastLog);
            }
        }
    }

    private List<String> buildJvmCommandLine(Path runBaseDir, String classpath) {
        var runTmpDir = runBaseDir.resolve("tmp");
        if (!runTmpDir.toFile().mkdirs()) {
            // runBaseDir should be empty: this should never happen (famous words)
            getLog().error("The run tmp directory already exists: " + runBaseDir);
            exit(1);
        }

        var command = new ArrayList<String>();
        command.add(JAVA_BIN_PATH.toString());
        command.addAll(JAVA_BASE_OPTIONS);
        command.addAll(List.of(
                "-javaagent:%s".formatted(agentJarPath),
                "-Djava.io.tmpdir=%s".formatted(runTmpDir),
                "-Dopendst.wars-dir=%s".formatted(instrumentedWarsDir)));
        if (jvmArguments != null && !jvmArguments.isBlank()) {
            command.addAll(asList(jvmArguments.split("\\s+")));
        }
        var debugArgs = debugArgs();
        if (debugArgs != null) {
            // Split args to handle multiple flags if passed by user
            command.addAll(asList(debugArgs.split("\\s+")));
        }
        command.addAll(asList("-cp", classpath, "com.pingidentity.opendst.maven.MethodRunner", testClass, testMethod));
        return List.copyOf(command);
    }

    private Path createRunBaseDir(int count) throws IOException {
        var runBaseDir = opendstBasePath
                .resolve(RUNS_BASE_DIR)
                .resolve(RUN_DIR_FORMAT.formatted(count))
                .toAbsolutePath();
        deleteDir(runBaseDir);
        if (!runBaseDir.toFile().mkdirs()) {
            getLog().error("The run base dir '%s' is unexpectedly already present".formatted(runBaseDir));
        }
        return runBaseDir;
    }

    private void deleteDir(Path runBaseDir) throws IOException {
        if (!exists(runBaseDir)) {
            return;
        } else if (!runBaseDir.toRealPath().startsWith(basePath)) {
            getLog().error("Directory '%s' will not be deleted as it is outside '%s'".formatted(runBaseDir, basePath));
            exit(1);
        }
        walkFileTree(runBaseDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Delete the file itself
                delete(file);
                return CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                // If an exception happened inside the directory, 'exc' won't be null.
                if (exc != null) {
                    throw exc;
                }
                delete(dir);
                return CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // Handle cases where a file exists but can't be accessed
                getLog().error("Failed to access file '%s' due to %s".formatted(file, exc.getMessage()));
                throw exc;
            }
        });
    }
}
