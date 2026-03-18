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

import static com.pingidentity.opendst.runner.Commons.INSTRUMENTED_APPS_DIR;
import static com.pingidentity.opendst.runner.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.runner.Commons.deleteRecursively;
import static java.lang.Runtime.getRuntime;
import static java.nio.file.FileSystems.newFileSystem;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static tools.jackson.databind.SerializationFeature.INDENT_OUTPUT;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pingidentity.opendst.runner.Assertion;
import com.pingidentity.opendst.runner.Bootstrap;
import com.pingidentity.opendst.runner.BuildRunner.BuildConfig;
import com.pingidentity.opendst.runner.DeploymentRunner.DeploymentDescriptor;
import com.pingidentity.opendst.runner.DeploymentRunner.DeploymentDescriptor.ServiceDescriptor;
import com.pingidentity.opendst.runner.DeploymentRunner.DeploymentDescriptor.TraceAuditorDescriptor;
import com.pingidentity.opendst.runner.OpenDstLogger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.apache.maven.plugin.descriptor.PluginDescriptor;
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
    private static final String OPENDST_SIMULATOR_JAR = "/META-INF/agents/opendst-agent.jar";

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

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor pluginDescriptor;

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

        // 2. Extract agent JAR
        var agentJarPath = extractAgentJar(basePath);

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
            buildJar(instrumentedAppsDir, agentJarPath, enrichedDescriptor, discoveredProperties);
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
     * <p>For each service, the enriched descriptor replaces the original source specification
     * ({@code artifact}, {@code dir}, or neither) with a canonical {@code dir} value matching
     * the {@code apps/} subdirectory name in the output JAR:
     * <ul>
     *   <li>{@code artifact} set — {@code dir} set to {@code artifactId-version}, {@code artifact} cleared</li>
     *   <li>{@code dir} set — {@code dir} set to the last path component</li>
     *   <li>Neither (current project) — {@code dir} set to the project's artifactId</li>
     * </ul>
     *
     * <p>The same enrichment applies to the optional trace auditor.
     */
    private DeploymentDescriptor enrichDescriptor(DeploymentDescriptor descriptor) {
        // Enrich services
        var enrichedServices = new LinkedHashMap<String, ServiceDescriptor>();
        for (var entry : descriptor.services().entrySet()) {
            var name = entry.getKey();
            var svc = entry.getValue();
            String enrichedDir;
            if (svc.artifact() != null && !svc.artifact().isBlank()) {
                enrichedDir = svc.appDir(name); // artifactId-version
            } else if (svc.dir() != null && !svc.dir().isBlank()) {
                enrichedDir = svc.appDir(name); // last path component
            } else {
                enrichedDir = project.getArtifactId(); // current project
            }
            enrichedServices.put(name, new ServiceDescriptor(null, enrichedDir, svc.className(), svc.ip(), svc.args()));
        }

        // Enrich trace auditor
        var traceAuditor = descriptor.traceAuditor();
        TraceAuditorDescriptor enrichedTraceAuditor = null;
        if (traceAuditor != null) {
            String enrichedDir;
            if (traceAuditor.artifact() != null && !traceAuditor.artifact().isBlank()) {
                enrichedDir = traceAuditor.appDir(); // artifactId-version
            } else if (traceAuditor.dir() != null && !traceAuditor.dir().isBlank()) {
                enrichedDir = traceAuditor.appDir(); // last path component
            } else {
                enrichedDir = project.getArtifactId(); // current project
            }
            enrichedTraceAuditor = new TraceAuditorDescriptor(null, enrichedDir, traceAuditor.className());
        }

        return new DeploymentDescriptor(enrichedServices, enrichedTraceAuditor);
    }

    /**
     * Resolves external artifacts and instruments all unique application sources.
     *
     * <p>Services (and the optional trace auditor) are grouped by their {@code appDir} to
     * deduplicate: multiple services sharing the same source are instrumented only once.
     * The original (non-enriched) descriptor is used to determine the source type.
     *
     * <p>Each unique source falls into one of three categories:
     * <ul>
     *   <li><b>artifact</b> — resolved from Maven repositories, unpacked, then instrumented</li>
     *   <li><b>dir</b> — an existing directory on disk, instrumented in-place</li>
     *   <li><b>current project</b> — {@code target/classes/} plus runtime dependency JARs</li>
     * </ul>
     */
    private Set<Assertion> resolveAndInstrument(Path basePath, Path instrumentedAppsDir)
            throws IOException, ArtifactResolutionException, MojoFailureException {
        var logger = new OpenDstLogger(mavenLogSink());

        // Re-parse original descriptor to know source types (before enrichment cleared artifact).
        var originalDescriptor = parseDescriptor();

        // Collect unique sources: enriched appDir → { artifact | dir | null (current project) }
        // We use a record to carry the source info needed for resolution.
        record SourceInfo(String artifact, String dir, String label) {}
        var uniqueSources = new LinkedHashMap<String, SourceInfo>();

        // From services
        for (var entry : originalDescriptor.services().entrySet()) {
            var serviceName = entry.getKey();
            var svc = entry.getValue();
            // Compute enriched appDir using the same logic as enrichDescriptor()
            String appDir;
            if (svc.artifact() != null && !svc.artifact().isBlank()) {
                appDir = svc.appDir(serviceName);
            } else if (svc.dir() != null && !svc.dir().isBlank()) {
                appDir = svc.appDir(serviceName);
            } else {
                appDir = project.getArtifactId();
            }
            uniqueSources.putIfAbsent(
                    appDir, new SourceInfo(svc.artifact(), svc.dir(), "service '%s'".formatted(serviceName)));
        }

        // From trace auditor
        if (originalDescriptor.traceAuditor() != null) {
            var ta = originalDescriptor.traceAuditor();
            String appDir;
            if (ta.artifact() != null && !ta.artifact().isBlank()) {
                appDir = ta.appDir();
            } else if (ta.dir() != null && !ta.dir().isBlank()) {
                appDir = ta.appDir();
            } else {
                appDir = project.getArtifactId();
            }
            uniqueSources.putIfAbsent(appDir, new SourceInfo(ta.artifact(), ta.dir(), "traceAuditor"));
        }

        try (var executor = newFixedThreadPool(getRuntime().availableProcessors() * 2)) {
            var instrumentation = new Instrumentation(basePath, instrumentedAppsDir, executor, logger);
            var allDiscovered = Instrumentation.newAssertionSet();

            // Instrument test classes once (shared across all sources)
            allDiscovered.addAll(instrumentation.instrumentTestClasses());

            for (var entry : uniqueSources.entrySet()) {
                var appDir = entry.getKey();
                var source = entry.getValue();

                if (source.artifact() != null && !source.artifact().isBlank()) {
                    // Artifact mode: resolve from Maven repos, unpack, instrument
                    var unpackedDir = resolveArtifact(basePath, appDir, source.artifact(), source.label());
                    allDiscovered.addAll(instrumentation.instrumentAppDir(appDir, unpackedDir));
                } else if (source.dir() != null && !source.dir().isBlank()) {
                    // Dir mode: instrument the existing directory
                    var sourceDir = basePath.resolve(source.dir());
                    if (!exists(sourceDir)) {
                        throw new IOException(
                                "%s references non-existent directory: %s".formatted(source.label(), sourceDir));
                    }
                    allDiscovered.addAll(instrumentation.instrumentAppDir(appDir, sourceDir));
                } else {
                    // Current project mode: instrument target/classes/ + runtime deps
                    allDiscovered.addAll(instrumentation.instrumentClasses(appDir, collectRuntimeDependencyJars()));
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
     * @param label    human-readable label for log messages (e.g., {@code "service 'alice'"})
     */
    private Path resolveArtifact(Path basePath, String appDir, String gav, String label)
            throws ArtifactResolutionException, IOException {
        getLog().info("Resolving artifact for %s: %s".formatted(label, gav));

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
     * Collects the project's runtime dependency JARs, excluding {@code opendst-sdk}
     * (which is compile-only — its classes are redirected by instrumentation).
     */
    private List<Path> collectRuntimeDependencyJars() {
        var jars = new ArrayList<Path>();
        for (var artifact : project.getArtifacts()) {
            if (!Artifact.SCOPE_COMPILE.equals(artifact.getScope())
                    && !Artifact.SCOPE_RUNTIME.equals(artifact.getScope())) {
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
            var yamlMapper = YAMLMapper.builder()
                    .disable(FAIL_ON_NULL_FOR_PRIMITIVES)
                    .disable(FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
            return yamlMapper.readValue(descriptor, DeploymentDescriptor.class);
        } catch (Exception e) {
            throw new MojoFailureException("Failed to parse deployment descriptor: " + descriptor.getAbsolutePath(), e);
        }
    }

    private Path extractAgentJar(Path basePath) throws MojoFailureException {
        try (var is = getClass().getResourceAsStream(OPENDST_SIMULATOR_JAR)) {
            if (is == null) {
                throw new MojoFailureException("Could not find embedded opendst-agent.jar");
            }
            var agentJar = basePath.resolve("target").resolve("opendst-package").resolve("opendst-agent.jar");
            createDirectories(agentJar.getParent());
            copy(is, agentJar, REPLACE_EXISTING);
            return agentJar;
        } catch (IOException e) {
            throw new MojoFailureException("Failed to extract opendst-agent.jar", e);
        }
    }

    /**
     * Builds the self-contained JAR with this layout:
     * <pre>
     * META-INF/
     *   MANIFEST.MF                     # Main-Class: Bootstrap
     *   opendst/
     *     assertions.json               # Serialized Set&lt;Assertion&gt;
     * com/pingidentity/opendst/runner/Bootstrap.class  # Bootstrap (only class at root)
     * system/
     *   opendst-agent.jar                # Java agent (full shaded JAR, also loaded by URLClassLoader)
     *   opendst-runner.jar              # Runner classes (BuildRunner, DeploymentRunner, etc.)
     *   jackson-databind.jar            # Library JARs
     *   jackson-core.jar
     *   jackson-annotations.jar
     *   jackson-dataformat-yaml.jar
     *   snakeyaml-engine.jar
     * apps/
     *   &lt;appDir&gt;/                       # Instrumented application content
     *     WEB-INF/
     *       classes.jar
     *       lib/
     * deployment.yaml                   # Enriched descriptor (all services have dir set)
     * build-config.json                 # Build-time defaults (JVM args, faults)
     * </pre>
     */
    private void buildJar(
            Path instrumentedAppsDir,
            Path agentJarPath,
            DeploymentDescriptor enrichedDescriptor,
            Set<Assertion> discoveredProperties)
            throws IOException {
        createDirectories(outputJar.toPath().getParent());

        var manifest = new Manifest();
        var mainAttributes = manifest.getMainAttributes();
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttributes.put(Attributes.Name.MAIN_CLASS, Bootstrap.class.getName());

        try (var jos = new JarOutputStream(newOutputStream(outputJar.toPath()), manifest)) {
            // 1. assertions.json
            addEntry(jos, "META-INF/opendst/assertions.json", JSON_MAPPER.writeValueAsBytes(discoveredProperties));

            // 2. Bootstrap.class at root — the only class needed for java -jar bootstrap
            addBootstrapClass(jos);

            // 3. system/ — all runtime JARs (loaded by Bootstrap's URLClassLoader after extraction)
            addEntry(jos, "system/opendst-agent.jar", readAllBytes(agentJarPath));
            addSystemJars(jos);

            // 4. apps/ — instrumented application artifacts
            addInstrumentedApps(jos, instrumentedAppsDir);

            // 5. deployment.yaml — enriched descriptor (serialized from object, not raw file)
            addEntry(jos, "deployment.yaml", serializeDescriptor(enrichedDescriptor));

            // 6. build-config.json — build-time defaults (runtime params are now CLI-only)
            var buildConfig = new BuildConfig(jvmArguments, defaultFaultsConfig());
            addEntry(jos, "build-config.json", JSON_MAPPER.writeValueAsBytes(buildConfig));
        }
    }

    /**
     * Serializes the enriched deployment descriptor to YAML bytes for baking into the JAR.
     */
    private static byte[] serializeDescriptor(DeploymentDescriptor descriptor) throws IOException {
        var yamlMapper = YAMLMapper.builder()
                .enable(INDENT_OUTPUT)
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        return yamlMapper.writeValueAsBytes(descriptor);
    }

    /** Returns the default faults configuration with network faults enabled. */
    private static BuildConfig.FaultsConfig defaultFaultsConfig() {
        return new BuildConfig.FaultsConfig(
                new BuildConfig.NetworkFaultsConfig(true, "100us", "800us", "100ms", "100ms"));
    }

    /**
     * Adds only {@link Bootstrap}{@code .class} at the JAR root. This is the sole class
     * that must be loadable by {@code java -jar} without a custom classloader. All other
     * classes are loaded by the {@link java.net.URLClassLoader} that {@code Bootstrap} creates
     * from {@code system/*.jar} after extraction.
     */
    private void addBootstrapClass(JarOutputStream jos) throws IOException {
        final Path classesDir;
        try {
            classesDir = Path.of(Bootstrap.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Failed to resolve Bootstrap class location", e);
        }
        var launcherRelPath = Bootstrap.class.getName().replace('.', '/') + ".class";

        if (isDirectory(classesDir)) {
            addEntry(jos, launcherRelPath, readAllBytes(classesDir.resolve(launcherRelPath)));
        } else {
            try (var jarFs = newFileSystem(classesDir, (ClassLoader) null)) {
                addEntry(jos, launcherRelPath, readAllBytes(jarFs.getPath(launcherRelPath)));
            }
        }
    }

    /**
     * Copies all runtime dependency JARs into {@code system/}. At runtime, the
     * {@link Bootstrap} builds a {@link java.net.URLClassLoader} from every
     * {@code system/*.jar} after extraction.
     *
     * <p>This includes the runner JAR (BuildRunner, DeploymentRunner, etc.),
     * Jackson, SnakeYAML, and opendst-sdk. {@code opendst-agent} is added separately
     * in {@link #buildJar} since it comes from the embedded agent JAR, not from
     * {@code pluginDescriptor}.
     */
    private void addSystemJars(JarOutputStream jos) throws IOException {
        for (var artifact : pluginDescriptor.getArtifacts()) {
            if (!isRuntimeDependency(artifact)) {
                continue;
            }
            // opendst-agent is already added as system/opendst-agent.jar from the embedded agent
            if ("opendst-agent".equals(artifact.getArtifactId())) {
                continue;
            }
            // The plugin JAR itself is not needed at runtime — all runtime classes are in opendst-runner
            if ("opendst-maven-plugin".equals(artifact.getArtifactId())) {
                continue;
            }
            var jarName = artifact.getArtifactId() + ".jar";
            addEntry(jos, "system/" + jarName, readAllBytes(artifact.getFile().toPath()));
        }
    }

    private boolean isRuntimeDependency(Artifact artifact) {
        var groupId = artifact.getGroupId();
        return groupId.startsWith("tools.jackson")
                || groupId.startsWith("com.fasterxml.jackson")
                || groupId.startsWith("org.snakeyaml")
                || "com.pingidentity.opendst".equals(groupId)
                || "info.picocli".equals(groupId);
    }

    /** Adapts Maven's {@link org.apache.maven.plugin.logging.Log} to {@link OpenDstLogger.Sink}. */
    private OpenDstLogger.Sink mavenLogSink() {
        var log = getLog();
        return new OpenDstLogger.Sink() {
            @Override
            public boolean isDebugEnabled() {
                return log.isDebugEnabled();
            }

            @Override
            public void debug(CharSequence content) {
                log.debug(content);
            }

            @Override
            public void info(CharSequence content) {
                log.info(content);
            }

            @Override
            public void warn(CharSequence content) {
                log.warn(content);
            }

            @Override
            public void error(CharSequence content) {
                log.error(content);
            }
        };
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
}
