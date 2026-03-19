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

import static com.pingidentity.opendst.Constants.APPS_DIR_PROPERTY;
import static com.pingidentity.opendst.Deployment.Image.image;
import static com.pingidentity.opendst.Deployment.Service.service;
import static com.pingidentity.opendst.Simulator.deploy;
import static com.pingidentity.opendst.Simulator.runSimulation;
import static java.lang.Runtime.getRuntime;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.setProperty;
import static java.net.InetAddress.getByName;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.walk;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pingidentity.opendst.Deployment.Image;
import com.pingidentity.opendst.Deployment.Service;
import com.pingidentity.opendst.sdk.TraceAuditor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Child JVM entry point for the self-contained JAR.
 *
 * <p>Instead of reflectively invoking a test method, this class reads the deployment descriptor and
 * calls {@link com.pingidentity.opendst.Simulator#deploy(java.util.List, java.util.List)}
 * followed by {@link com.pingidentity.opendst.Simulator#runSimulation}.
 *
 * <p>Arguments: {@code <deploymentDir> <ignored>}
 *
 * <p>The deployment directory is expected to contain:
 * <ul>
 *   <li>{@code deployment.yaml} — the enriched deployment descriptor (all services have {@code dir} set)</li>
 *   <li>{@code apps/} — instrumented application artifacts</li>
 * </ul>
 */
public final class OpenDSTExecutor {
    public static void main(String[] args) {
        if (args.length < 1) {
            err.println("Usage: java OpenDSTExecutor <deploymentDir>");
            exit(1);
        }

        try {
            var deploymentDir = Path.of(args[0]);
            var descriptorFile = deploymentDir.resolve("deployment.yaml");

            // Parse deployment descriptor
            var yamlMapper = YAMLMapper.builder()
                    .disable(FAIL_ON_NULL_FOR_PRIMITIVES)
                    .disable(FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
            var descriptor = yamlMapper.readValue(descriptorFile.toFile(), DeploymentDescriptor.class);

            // Build Image and Service lists from the map-based descriptor.
            // One Image per service (imageName = serviceName) since class differs per service.
            // The opendst-agent JAR must be on each service's classpath so that the
            // URLClassLoader (parented to getPlatformClassLoader()) can resolve
            // AssertImpl and other opendst-agent classes referenced by instrumented code.
            var coreJarUrl =
                    deploymentDir.resolve("system/opendst-agent.jar").toUri().toURL();
            var extraClasspath = new URL[] {coreJarUrl};
            var images = new ArrayList<Image>();
            var services = new ArrayList<Service>();

            for (var entry : descriptor.services().entrySet()) {
                var serviceName = entry.getKey();
                var serviceDescriptor = entry.getValue();
                // appDir(serviceName) returns the apps/ subdirectory for this service's source
                var appDir = Path.of(serviceDescriptor.appDir(serviceName));
                images.add(image(serviceName, appDir, serviceDescriptor.className(), extraClasspath));
                services.add(service(
                        serviceName, serviceName, getByName(serviceDescriptor.ip()), serviceDescriptor.argsArray()));
            }

            // Set the apps-dir property to point to the apps/ directory inside the extraction.
            // This must happen before Deployment class initialization reads the apps-dir property.
            var appsDir = deploymentDir.resolve("apps");
            setProperty(APPS_DIR_PROPERTY, appsDir.toAbsolutePath().toString());

            // Resolve trace auditor if specified. The trace auditor is self-contained: its source
            // is identified by its own appDir() (from dir/artifact), not by referencing a service.
            TraceAuditor traceAuditor = _ -> {};
            if (descriptor.traceAuditor() != null) {
                var auditorAppDir = descriptor.traceAuditor().appDir();
                if (auditorAppDir == null) {
                    throw new IllegalStateException(
                            "Trace auditor has no 'dir' or 'artifact' — the deployment descriptor may not have been enriched by the build plugin");
                }
                var auditorClassLoader = nodeClassLoader(appsDir, auditorAppDir);
                var auditorClass = Class.forName(descriptor.traceAuditor().className(), true, auditorClassLoader);
                traceAuditor =
                        (TraceAuditor) auditorClass.getDeclaredConstructor().newInstance();
            }

            // Run the simulation
            runSimulation(
                    () -> {
                        deploy(images, services);
                        return null;
                    },
                    traceAuditor);

        } catch (ReflectiveOperationException e) {
            err.println("Failed to instantiate trace auditor");
            e.printStackTrace();
            getRuntime().halt(1);
        } catch (Exception e) {
            err.println("OpenDSTExecutor failed");
            e.printStackTrace();
            getRuntime().halt(1);
        }
    }

    /**
     * Builds a {@link URLClassLoader} that can see the node classes for the given app directory.
     *
     * <p>Mirrors the classpath logic in {@code Deployment.classPath()} — walks
     * {@code WEB-INF/lib/} for JARs, then checks for {@code WEB-INF/classes.jar} and
     * {@code WEB-INF/classes/}. The parent is the system classloader so that the
     * {@link TraceAuditor} interface (from {@code opendst-sdk}) remains visible for the cast.
     *
     * @param appsDir  the root {@code apps/} directory inside the extraction
     * @param appDir   the subdirectory name under {@code apps/} (e.g., {@code opendst-testapp})
     */
    private static URLClassLoader nodeClassLoader(Path appsDir, String appDir) throws IOException {
        var webInfDir = appsDir.resolve(appDir).resolve("WEB-INF");
        var urls = new ArrayList<URL>();

        // WEB-INF/lib/*.jar
        var libDir = webInfDir.resolve("lib");
        if (exists(libDir)) {
            try (var libJars = walk(libDir).sorted()) {
                libJars.filter(p -> p.toString().toLowerCase().endsWith(".jar") && isRegularFile(p))
                        .forEach(p -> {
                            try {
                                urls.add(p.toUri().toURL());
                            } catch (MalformedURLException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        }
        // WEB-INF/classes.jar
        var classesJar = webInfDir.resolve("classes.jar");
        if (exists(classesJar)) {
            urls.add(classesJar.toUri().toURL());
        }
        // WEB-INF/classes/
        var classesDir = webInfDir.resolve("classes");
        if (exists(classesDir)) {
            urls.add(classesDir.toUri().toURL());
        }
        return new URLClassLoader(
                "trace-auditor-loader", urls.toArray(URL[]::new), OpenDSTExecutor.class.getClassLoader());
    }

    /**
     * Data model for the YAML deployment descriptor used by the {@code build} mojo.
     *
     * <p>The descriptor defines the deployment topology using a docker-compose-like format:
     * services (as a named map) and an optional trace auditor. Each service carries its own
     * source and class declaration. Orchestration concerns (faults, duration, fork count)
     * are configured separately in {@link BuildRunner.BuildConfig} and via CLI arguments.
     *
     * <p>Example YAML:
     * <pre>{@code
     * services:
     *   server:
     *     class: com.example.App$Server
     *     ip: 10.0.0.1
     *     args: ["8080"]
     *   client:
     *     class: com.example.App$Client
     *     ip: 10.0.0.2
     *     args: ["10.0.0.1", "8080"]
     *
     * traceAuditor:
     *   class: com.example.MyTraceAuditor
     * }</pre>
     */
    public record DeploymentDescriptor(Map<String, ServiceDescriptor> services, TraceAuditorDescriptor traceAuditor) {

        /**
         * Describes a service with three mutually exclusive source modes:
         * <ol>
         *   <li>{@code artifact} — a Maven GAV coordinate resolved from Maven repositories</li>
         *   <li>{@code dir} — a local directory path relative to the project basedir</li>
         *   <li>Neither — the current project's {@code target/classes/} and runtime dependencies</li>
         * </ol>
         *
         * <p>At build time, the plugin enriches every service with a {@code dir} value pointing
         * to the actual {@code apps/} subdirectory name in the output JAR. The baked descriptor
         * therefore always has {@code dir} set on every service.
         */
        public record ServiceDescriptor(
                String artifact,
                String dir,
                @JsonProperty("class") String className,
                String ip,
                List<String> args) {

            /**
             * Returns the {@code apps/} subdirectory name for this service's source.
             *
             * <ul>
             *   <li>{@code dir} set — last path component (e.g., {@code target/apps/foo} &rarr; {@code foo})</li>
             *   <li>{@code artifact} set — {@code artifactId-version} parsed from the GAV coordinate</li>
             *   <li>Neither — falls back to the given {@code serviceName}</li>
             * </ul>
             *
             * @param serviceName the service name (map key), used as fallback
             */
            public String appDir(String serviceName) {
                if (dir != null && !dir.isBlank()) {
                    return Path.of(dir).getFileName().toString();
                }
                if (artifact != null && !artifact.isBlank()) {
                    var parts = artifact.split(":");
                    if (parts.length < 2) {
                        throw new IllegalArgumentException(
                                "Malformed artifact coordinate (expected groupId:artifactId[:...]:version): "
                                        + artifact);
                    }
                    return parts[1] + "-" + parts[parts.length - 1];
                }
                return serviceName;
            }

            String[] argsArray() {
                return args == null ? new String[0] : args.toArray(String[]::new);
            }
        }

        /**
         * Trace auditor configuration with the same source model as services.
         *
         * <p>The trace auditor class is loaded from the source identified by {@code artifact},
         * {@code dir}, or the current project (if neither is set). This makes the trace auditor
         * self-describing rather than referencing a service for its classloader.
         */
        public record TraceAuditorDescriptor(
                String artifact,
                String dir,
                @JsonProperty("class") String className) {

            /**
             * Returns the {@code apps/} subdirectory name for the trace auditor's source.
             *
             * <ul>
             *   <li>{@code dir} set — last path component</li>
             *   <li>{@code artifact} set — {@code artifactId-version}</li>
             *   <li>Neither — returns {@code null} (current project; plugin must enrich before baking)</li>
             * </ul>
             */
            public String appDir() {
                if (dir != null && !dir.isBlank()) {
                    return Path.of(dir).getFileName().toString();
                }
                if (artifact != null && !artifact.isBlank()) {
                    var parts = artifact.split(":");
                    if (parts.length < 2) {
                        throw new IllegalArgumentException(
                                "Malformed artifact coordinate (expected groupId:artifactId[:...]:version): "
                                        + artifact);
                    }
                    return parts[1] + "-" + parts[parts.length - 1];
                }
                return null;
            }
        }
    }
}
