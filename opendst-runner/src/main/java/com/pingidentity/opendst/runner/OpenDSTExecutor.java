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

import static com.pingidentity.opendst.Simulator.runSimulation;
import static com.pingidentity.opendst.Simulator.startNode;
import static java.lang.ClassLoader.getPlatformClassLoader;
import static java.lang.Runtime.getRuntime;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.net.InetAddress.getByName;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.walk;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES;
import static tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

import com.pingidentity.opendst.common.DeploymentDescriptor;
import com.pingidentity.opendst.sdk.TraceAuditor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Child JVM entry point for the self-contained JAR.
 *
 * <p>Reads a {@code deployment.yaml} descriptor from the deployment directory, resolves
 * classpaths from the {@code apps/} subdirectories, and starts each service as a
 * classloader-isolated node inside a deterministic simulation.
 *
 * <p>Arguments: {@code <deploymentDir>}
 *
 * <p>The deployment directory is expected to contain:
 * <ul>
 *   <li>{@code deployment.yaml} — the enriched deployment descriptor (all services have {@code dir} set)</li>
 *   <li>{@code apps/} — instrumented application artifacts</li>
 *   <li>{@code system/opendst-agent.jar} — the agent JAR added to each service's classpath</li>
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

            // The opendst-agent JAR must be on each service's classpath so that the
            // URLClassLoader (parented to getPlatformClassLoader()) can resolve
            // AssertImpl and other opendst-agent classes referenced by instrumented code.
            var appsDir = deploymentDir.resolve("apps");
            var coreJarUrl =
                    deploymentDir.resolve("system/opendst-agent.jar").toUri().toURL();

            // Resolve trace auditor if specified. The trace auditor is self-contained: its source
            // is identified by its own appDir() (from dir/artifact), not by referencing a service.
            TraceAuditor traceAuditor = _ -> {};
            if (descriptor.traceAuditor() != null) {
                var auditorAppDir = descriptor.traceAuditor().appDir();
                if (auditorAppDir == null) {
                    throw new IllegalStateException(
                            "Trace auditor has no 'dir' or 'artifact' — the deployment descriptor may not have been enriched by the build plugin");
                }
                var auditorClassLoader = classLoader(
                        "trace-auditor-loader", appsDir.resolve(auditorAppDir), OpenDSTExecutor.class.getClassLoader());
                var auditorClass = Class.forName(descriptor.traceAuditor().className(), true, auditorClassLoader);
                traceAuditor =
                        (TraceAuditor) auditorClass.getDeclaredConstructor().newInstance();
            }

            // Run the simulation — start each service as a classloader-isolated node.
            runSimulation(
                    () -> {
                        for (var entry : descriptor.services().entrySet()) {
                            var serviceName = entry.getKey();
                            var svc = entry.getValue();
                            var appDir = appsDir.resolve(svc.appDir(serviceName));
                            var serviceClassLoader =
                                    classLoader(serviceName, appDir, getPlatformClassLoader(), coreJarUrl);
                            var mainMethod = serviceClassLoader
                                    .loadClass(svc.className())
                                    .getMethod("main", String[].class);
                            startNode(
                                    serviceName, getByName(svc.ip()), serviceClassLoader, mainMethod, svc.argsArray());
                        }
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
     * Builds a {@link URLClassLoader} for a node whose classes live under {@code appDir/WEB-INF/}.
     *
     * <p>Walks {@code WEB-INF/lib/} for JARs, then checks for {@code WEB-INF/classes.jar}
     * and {@code WEB-INF/classes/}. Any additional URLs (e.g. the opendst-agent JAR) are
     * appended after the application classpath.
     *
     * @param name      the classloader name (used for debugging)
     * @param appDir    the application directory (contains {@code WEB-INF/})
     * @param parent    the parent classloader
     * @param extraUrls additional URLs appended after the application classpath
     */
    private static URLClassLoader classLoader(String name, Path appDir, ClassLoader parent, URL... extraUrls)
            throws IOException {
        var webInfDir = appDir.resolve("WEB-INF");
        var urls = new ArrayList<URL>();

        // WEB-INF/lib/*.jar
        var libDir = webInfDir.resolve("lib");
        try (var libJars = walk(libDir).sorted()) {
            libJars.filter(p -> p.toString().toLowerCase().endsWith(".jar") && isRegularFile(p))
                    .forEach(p -> {
                        try {
                            urls.add(p.toUri().toURL());
                        } catch (MalformedURLException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (NoSuchFileException e) {
            // No lib directory — that's fine
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
        // Extra URLs (e.g. opendst-agent.jar)
        for (var extra : extraUrls) {
            urls.add(extra);
        }
        return new URLClassLoader(name, urls.toArray(URL[]::new), parent);
    }
}
