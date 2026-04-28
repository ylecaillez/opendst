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

import static com.pingidentity.opendst.maven.DeploymentDescriptor.PROJECT_ARTIFACT_ID_KEY;
import static com.pingidentity.opendst.maven.DeploymentDescriptor.Source.Project.Scope.TEST;
import static java.lang.Runtime.getRuntime;
import static java.nio.file.FileSystems.newFileSystem;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.delete;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofNanos;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static tools.jackson.core.StreamReadFeature.AUTO_CLOSE_SOURCE;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static tools.jackson.databind.cfg.EnumFeature.WRITE_ENUMS_USING_TO_STRING;

import com.pingidentity.opendst.common.Assertion;
import com.pingidentity.opendst.common.BuildConfig;
import com.pingidentity.opendst.common.Faults;
import com.pingidentity.opendst.common.RuntimeDeployment;
import com.pingidentity.opendst.common.RuntimeDeployment.RuntimeAuditor;
import com.pingidentity.opendst.common.RuntimeDeployment.RuntimeService;
import com.pingidentity.opendst.maven.DeploymentDescriptor.ServiceDescriptor;
import com.pingidentity.opendst.maven.DeploymentDescriptor.Source;
import com.pingidentity.opendst.maven.DeploymentDescriptor.Source.Project.Scope;
import com.pingidentity.opendst.maven.DeploymentDescriptor.TraceAuditorDescriptor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import tools.jackson.databind.InjectableValues;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The {@code build} goal produces a self-contained executable JAR that runs an OpenDST
 * simulation without Maven. All bytecode instrumentation happens at build time.
 *
 * <p>Usage: {@code mvn opendst:build -Dopendst.descriptor=deployment.yaml}
 *
 * <p>The produced JAR can then be run with: {@code java -jar target/<finalName>-opendst.jar}
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.TEST)
public class BuildMojo extends AbstractMojo {
    private static final String OPENDST_AGENT_JAR = "/META-INF/agents/opendst-agent.jar";
    private static final String OPENDST_RUNNER_JAR = "/META-INF/agents/opendst-runner.jar";
    private static final String BOOTSTRAP_CLASS_NAME = "com.pingidentity.opendst.runner.Bootstrap";
    private static final String INSTRUMENTED_APPS_DIR = "instrumented-apps";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .disable(FAIL_ON_TRAILING_TOKENS)
            .disable(FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(AUTO_CLOSE_SOURCE)
            .enable(WRITE_ENUMS_USING_TO_STRING)
            .build();

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @SuppressWarnings("deprecation")
    @org.apache.maven.plugins.annotations.Component
    private RepositorySystem repositorySystem;

    @SuppressWarnings("deprecation")
    @org.apache.maven.plugins.annotations.Component
    private MavenProjectHelper projectHelper;

    @Parameter(property = "opendst.descriptor", defaultValue = "${project.basedir}/deployment.yaml")
    private File descriptor;

    @Parameter(
            property = "opendst.outputJar",
            defaultValue = "${project.build.directory}/${project.build.finalName}-opendst.jar")
    private File outputJar;

    @Parameter(property = "opendst.jvmArguments")
    private String jvmArguments;

    @Parameter(property = "opendst.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping opendst:build because opendst.skip is enabled.");
            return;
        }

        Path basePath;
        try {
            basePath = project.getBasedir().toPath().toRealPath();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to resolve base path", e);
        }

        // 1. Parse deployment descriptor and enrich services
        var deploymentDescriptor = parseDescriptor();
        var enrichedDescriptor = enrichDescriptor(deploymentDescriptor);

        // 2. Extract embedded JARs (agent, runner) from plugin classpath resources
        var agentJarPath = extractEmbeddedJar(basePath, OPENDST_AGENT_JAR, "opendst-agent.jar");
        var runnerJarPath = extractEmbeddedJar(basePath, OPENDST_RUNNER_JAR, "opendst-runner.jar");

        // 2b. Generate opendst-patch.jar (patched VirtualThread + SimulatorThread)
        var patchModuleJarPath =
                basePath.resolve("target").resolve("opendst-package").resolve("opendst-patch.jar");
        try {
            PatchModuleGenerator.generate(patchModuleJarPath);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate opendst-patch.jar", e);
        }

        // 3. Resolve external artifacts and instrument all unique sources
        var opendstBasePath = basePath.resolve("target").resolve("opendst-package");
        var instrumentedAppsDir = opendstBasePath.resolve(INSTRUMENTED_APPS_DIR);
        try {
            deleteRecursively(basePath, instrumentedAppsDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to clean instrumented apps directory", e);
        }

        Set<Assertion> discoveredProperties;
        try {
            discoveredProperties = resolveAndInstrument(basePath, instrumentedAppsDir);
        } catch (ArtifactResolutionException e) {
            throw new MojoFailureException("Failed to resolve external artifact: " + e.getMessage(), e);
        } catch (Instrumentation.AssertionValidationException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoFailureException("Failed to instrument application code", e);
        }

        // 4. Build the self-contained JAR
        try {
            buildJar(
                    instrumentedAppsDir,
                    agentJarPath,
                    runnerJarPath,
                    patchModuleJarPath,
                    enrichedDescriptor,
                    discoveredProperties);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to build self-contained JAR", e);
        }

        getLog().info("Built self-contained JAR: " + outputJar.getAbsolutePath());
        getLog().info("Run with: java -jar " + outputJar.getName());
        projectHelper.attachArtifact(project, "jar", "opendst", outputJar);
    }

    /**
     * Enriches the parsed deployment descriptor for baking into the output JAR.
     *
     * <p>Every service's source is replaced with a {@link Source.Dir} whose path matches the
     * {@code apps/} subdirectory name in the output JAR — computed by {@link Source#appDir()}.
     * The same enrichment applies to the optional trace auditor.
     */
    private DeploymentDescriptor enrichDescriptor(DeploymentDescriptor descriptor) {
        var enrichedServices = new LinkedHashMap<String, ServiceDescriptor>();
        for (var entry : descriptor.services().entrySet()) {
            var svc = entry.getValue();
            var enrichedDir = svc.appDir();
            enrichedServices.put(
                    entry.getKey(),
                    new ServiceDescriptor(new Source.Dir(enrichedDir), svc.className(), svc.ip(), svc.args()));
        }

        TraceAuditorDescriptor enrichedTraceAuditor = null;
        if (descriptor.traceAuditor() != null) {
            var ta = descriptor.traceAuditor();
            var enrichedDir = ta.appDir();
            enrichedTraceAuditor = new TraceAuditorDescriptor(new Source.Dir(enrichedDir), ta.className());
        }

        return new DeploymentDescriptor(enrichedServices, enrichedTraceAuditor);
    }

    /**
     * Resolves external artifacts and instruments all unique application sources.
     *
     * <p>Services (and the optional trace auditor) are grouped by their {@link Source#appDir()}
     * to deduplicate: multiple services sharing the same source are instrumented only once.
     *
     * <p>Each unique source is dispatched via an exhaustive {@code switch} on {@link Source}:
     * <ul>
     *   <li>{@link Source.Artifact} — resolved from Maven repositories, unpacked, then instrumented</li>
     *   <li>{@link Source.Dir} — an existing directory on disk, instrumented in-place</li>
     *   <li>{@link Source.Project} — class directories plus dependency JARs, controlled by scope</li>
     * </ul>
     */
    @SuppressWarnings("PMD.UnusedLocalVariable") // record pattern bindings required by switch
    private Set<Assertion> resolveAndInstrument(Path basePath, Path instrumentedAppsDir)
            throws IOException, ArtifactResolutionException, MojoFailureException {
        var log = getLog();

        // Re-parse original descriptor to know source types (before enrichment).
        var originalDescriptor = parseDescriptor();

        // Collect unique sources: appDir → Source
        var uniqueSources = new LinkedHashMap<String, Source>();
        for (var svc : originalDescriptor.services().values()) {
            uniqueSources.putIfAbsent(svc.appDir(), svc.source());
        }
        if (originalDescriptor.traceAuditor() != null) {
            var ta = originalDescriptor.traceAuditor();
            uniqueSources.putIfAbsent(ta.appDir(), ta.source());
        }

        try (var executor = newFixedThreadPool(getRuntime().availableProcessors() * 2)) {
            var instrumentation = new Instrumentation(basePath, instrumentedAppsDir, executor, log);
            var allDiscovered = Instrumentation.newAssertionSet();

            for (var entry : uniqueSources.entrySet()) {
                var appDir = entry.getKey();
                var source = entry.getValue();

                switch (source) {
                    case Source.Artifact(var gav, var appDirName) -> {
                        var unpackedDir = resolveArtifact(basePath, appDir, gav);
                        allDiscovered.addAll(instrumentation.instrumentAppDir(appDir, unpackedDir));
                    }
                    case Source.Dir(var path) -> {
                        var sourceDir = basePath.resolve(path);
                        if (!exists(sourceDir)) {
                            throw new IOException("Non-existent directory: " + sourceDir);
                        }
                        allDiscovered.addAll(instrumentation.instrumentAppDir(appDir, sourceDir));
                    }
                    case Source.Project(var scope, var artifactId) -> {
                        var classesDirs = scope.classesDirs(basePath);
                        var dependencyJars = collectDependencyJars(scope);
                        allDiscovered.addAll(instrumentation.instrumentClasses(appDir, classesDirs, dependencyJars));
                    }
                }
            }
            return allDiscovered;
        }
    }

    /**
     * Resolves a Maven artifact from a GAV coordinate, unpacks it to a temporary staging
     * directory, and returns the path.
     *
     * @param basePath the project base directory
     * @param appDir   the {@code apps/} subdirectory name (used for staging path)
     * @param gav      the Maven GAV coordinate (e.g., {@code groupId:artifactId:packaging:version})
     */
    private Path resolveArtifact(Path basePath, String appDir, String gav)
            throws ArtifactResolutionException, IOException {
        getLog().info("Resolving artifact: " + gav);

        var artifact = new DefaultArtifact(gav);
        var request = new ArtifactRequest(artifact, project.getRemoteProjectRepositories(), null);
        var resolved = repositorySystem.resolveArtifact(session.getRepositorySession(), request);

        var stagingDir = basePath.resolve("target")
                .resolve("opendst-package")
                .resolve("staging")
                .resolve(appDir);
        unpackArchive(resolved.getArtifact().getFile().toPath(), stagingDir);
        return stagingDir;
    }

    /**
     * Collects dependency JARs for the given scope, excluding {@code opendst-sdk}
     * (which is compile-only — its classes are redirected by instrumentation).
     *
     * <ul>
     *   <li>{@link Scope#COMPILE} — compile + runtime scoped artifacts</li>
     *   <li>{@link Scope#TEST} — compile + runtime + test scoped artifacts</li>
     * </ul>
     */
    private List<Path> collectDependencyJars(Scope scope) {
        var includeTest = scope == TEST;
        var jars = new ArrayList<Path>();
        for (var artifact : project.getArtifacts()) {
            var artifactScope = artifact.getScope();
            if (!Artifact.SCOPE_COMPILE.equals(artifactScope)
                    && !Artifact.SCOPE_RUNTIME.equals(artifactScope)
                    && !(includeTest && Artifact.SCOPE_TEST.equals(artifactScope))) {
                continue;
            }
            if ("opendst-sdk".equals(artifact.getArtifactId())
                    && "com.pingidentity.opendst".equals(artifact.getGroupId())) {
                continue;
            }
            var jarFile = artifact.getFile();
            if (jarFile != null && jarFile.getName().endsWith(".jar")) {
                jars.add(jarFile.toPath());
            }
        }
        return jars;
    }

    /**
     * Unpacks an archive (WAR or JAR) into the given target directory, producing an
     * exploded layout with {@code WEB-INF/classes/} and {@code WEB-INF/lib/}.
     */
    private static void unpackArchive(Path archiveFile, Path targetDir) throws IOException {
        createDirectories(targetDir);
        try (var archiveFs = newFileSystem(archiveFile, (ClassLoader) null)) {
            var root = archiveFs.getPath("/");
            try (var stream = walk(root)) {
                for (var entry : stream.toList()) {
                    var relativePath = root.relativize(entry).toString();
                    if (relativePath.isEmpty()) {
                        continue;
                    }
                    var target = targetDir.resolve(relativePath).normalize();
                    if (!target.startsWith(targetDir.normalize())) {
                        throw new IOException(
                                "Zip-Slip: entry '" + relativePath + "' resolves outside target directory");
                    }
                    if (isDirectory(entry)) {
                        createDirectories(target);
                    } else {
                        createDirectories(target.getParent());
                        copy(entry, target, REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private DeploymentDescriptor parseDescriptor() throws MojoFailureException {
        if (!descriptor.isFile()) {
            throw new MojoFailureException("Deployment descriptor not found: " + descriptor.getAbsolutePath());
        }
        try {
            return YAMLMapper.builder()
                    .disable(FAIL_ON_NULL_FOR_PRIMITIVES)
                    .disable(FAIL_ON_UNKNOWN_PROPERTIES)
                    .build()
                    .readerFor(DeploymentDescriptor.class)
                    .with(new InjectableValues.Std().addValue(PROJECT_ARTIFACT_ID_KEY, project.getArtifactId()))
                    .readValue(descriptor);
        } catch (Exception e) {
            throw new MojoFailureException("Failed to parse deployment descriptor: " + descriptor.getAbsolutePath(), e);
        }
    }

    /**
     * Extracts an embedded JAR from the plugin's classpath resources to a file
     * under {@code target/opendst-package/}.
     *
     * @param basePath     the project base directory
     * @param resourcePath the classpath resource path (e.g. {@code /META-INF/agents/opendst-agent.jar})
     * @param fileName     the output file name (e.g. {@code opendst-agent.jar})
     * @return the path to the extracted JAR
     */
    private Path extractEmbeddedJar(Path basePath, String resourcePath, String fileName) throws MojoFailureException {
        try (var is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new MojoFailureException("Could not find embedded " + fileName);
            }
            var jarPath = basePath.resolve("target").resolve("opendst-package").resolve(fileName);
            createDirectories(jarPath.getParent());
            copy(is, jarPath, REPLACE_EXISTING);
            return jarPath;
        } catch (IOException e) {
            throw new MojoFailureException("Failed to extract " + fileName, e);
        }
    }

    /**
     * Builds the self-contained JAR with this layout:
     * <pre>
     * META-INF/
     *   MANIFEST.MF                     # Main-Class: Bootstrap
     *   opendst/
     *     assertions.json               # Serialized Set&lt;Assertion&gt;
     *     deployment.json               # Runtime view of the enriched descriptor
     *     build-config.json             # Build-time defaults (JVM args, faults)
     * com/pingidentity/opendst/runner/Bootstrap.class  # Bootstrap (only class at root)
     * system/
     *   opendst-agent.jar               # Java agent (shaded fat JAR)
     *   opendst-runner.jar              # Runner (shaded: picocli + Jackson + SnakeYAML + common)
     *   opendst-patch.jar               # Patched VirtualThread + SimulatorThread (--patch-module)
     * apps/
     *   &lt;appDir&gt;/                       # Instrumented application content
     *     WEB-INF/
     *       classes.jar
     *       lib/
     * </pre>
     */
    private void buildJar(
            Path instrumentedAppsDir,
            Path agentJarPath,
            Path runnerJarPath,
            Path patchModuleJarPath,
            DeploymentDescriptor enrichedDescriptor,
            Set<Assertion> discoveredProperties)
            throws IOException {
        createDirectories(outputJar.toPath().getParent());

        var manifest = new Manifest();
        var mainAttributes = manifest.getMainAttributes();
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttributes.put(Attributes.Name.MAIN_CLASS, BOOTSTRAP_CLASS_NAME);

        try (var jos = new JarOutputStream(newOutputStream(outputJar.toPath()), manifest)) {
            // 1. assertions.json
            addEntry(jos, "META-INF/opendst/assertions.json", JSON_MAPPER.writeValueAsBytes(discoveredProperties));

            // 2. Bootstrap.class at root — the only class needed for java -jar bootstrap
            addBootstrapClass(jos, runnerJarPath);

            // 3. system/ — all runtime JARs (loaded by Bootstrap's URLClassLoader after extraction)
            addEntry(jos, "system/opendst-agent.jar", readAllBytes(agentJarPath));
            addEntry(jos, "system/opendst-runner.jar", readAllBytes(runnerJarPath));
            addEntry(jos, "system/opendst-patch.jar", readAllBytes(patchModuleJarPath));

            // 4. apps/ — instrumented application artifacts
            addInstrumentedApps(jos, instrumentedAppsDir);

            // 5. deployment.json — runtime view of the enriched descriptor (jackson-jr-friendly)
            addEntry(
                    jos,
                    "META-INF/opendst/deployment.json",
                    JSON_MAPPER.writeValueAsBytes(toRuntimeDeployment(enrichedDescriptor)));

            // 6. build-config.json — build-time defaults (runtime params are now CLI-only)
            var buildConfig = new BuildConfig(jvmArguments, defaultFaultsConfig());
            addEntry(jos, "META-INF/opendst/build-config.json", JSON_MAPPER.writeValueAsBytes(buildConfig));
        }
    }

    /** Returns the default faults configuration with network faults enabled. */
    private static Faults.Config defaultFaultsConfig() {
        return new Faults.Config(new Faults.Config.NetworkConfig(
                true,
                ofNanos(100_000), // latency minimum
                ofNanos(800_000), // latency fast
                ofMillis(100), // latency slow
                ofMillis(100), // clogging max
                0.001, // connection-reset probability
                0.001)); // timeout probability
    }

    /**
     * Converts an enriched {@link DeploymentDescriptor} into the runtime-only view that
     * gets baked into the self-contained JAR as {@code META-INF/opendst/deployment.json}.
     *
     * <p>By contract, every service's source has been resolved to a {@link Source.Dir}
     * during enrichment (see {@code enrichDescriptor}); this method enforces that
     * invariant explicitly.
     */
    private static RuntimeDeployment toRuntimeDeployment(DeploymentDescriptor descriptor) {
        var services = new LinkedHashMap<String, RuntimeService>();
        descriptor
                .services()
                .forEach((name, svc) -> services.put(
                        name, new RuntimeService(dirOf(svc.source()), svc.className(), svc.ip(), svc.args())));
        var auditor = descriptor.traceAuditor() == null
                ? null
                : new RuntimeAuditor(
                        dirOf(descriptor.traceAuditor().source()),
                        descriptor.traceAuditor().className());
        return new RuntimeDeployment(services, auditor);
    }

    private static String dirOf(Source source) {
        if (source instanceof Source.Dir dir) {
            return dir.path();
        }
        throw new IllegalStateException("Expected enriched descriptor with Source.Dir, got "
                + source.getClass().getSimpleName());
    }

    /**
     * Adds only {@code Bootstrap.class} at the JAR root. This is the sole class
     * that must be loadable by {@code java -jar} without a custom classloader. All other
     * classes are loaded by the {@link java.net.URLClassLoader} that {@code Bootstrap} creates
     * from {@code system/*.jar} after extraction.
     *
     * <p>Reads the class bytes from the embedded {@code opendst-runner} JAR.
     *
     * @param runnerJarPath path to the extracted opendst-runner JAR
     */
    private void addBootstrapClass(JarOutputStream jos, Path runnerJarPath) throws IOException {
        var launcherRelPath = BOOTSTRAP_CLASS_NAME.replace('.', '/') + ".class";

        try (var jarFs = newFileSystem(runnerJarPath, (ClassLoader) null)) {
            addEntry(jos, launcherRelPath, readAllBytes(jarFs.getPath(launcherRelPath)));
        }
    }

    private void addInstrumentedApps(JarOutputStream jos, Path instrumentedAppsDir) throws IOException {
        if (!exists(instrumentedAppsDir)) {
            return;
        }
        try (var stream = walk(instrumentedAppsDir)) {
            for (var p : stream.filter(Files::isRegularFile).toList()) {
                var relativePath = instrumentedAppsDir.relativize(p).toString().replace('\\', '/');
                addEntry(jos, "apps/" + relativePath, readAllBytes(p));
            }
        }
    }

    private static void addEntry(JarOutputStream jos, String name, byte[] content) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content);
        jos.closeEntry();
    }

    /** Recursively deletes a directory, with safety checks to prevent deleting outside the project. */
    private static void deleteRecursively(Path basePath, Path directoryToDelete) throws IOException {
        if (!exists(directoryToDelete)) {
            return;
        }
        var absoluteBase = basePath.toAbsolutePath();
        if (absoluteBase.getParent() == null) {
            throw new IllegalArgumentException("The base directory '%s' must not be the root".formatted(basePath));
        } else if (!directoryToDelete.toRealPath().startsWith(absoluteBase)) {
            throw new IllegalArgumentException(
                    "Directory '%s' will not be deleted as it is outside '%s'".formatted(directoryToDelete, basePath));
        }
        try (var stream = walk(directoryToDelete)) {
            var paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (var path : paths) {
                try {
                    delete(path);
                } catch (NoSuchFileException ignored) {
                }
            }
        }
    }
}
