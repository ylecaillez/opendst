/*
 * Copyright 2025-2026 Ping Identity Corporation
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
package com.pingidentity.opendst;

import static com.pingidentity.opendst.Simulator.startNode;
import static java.lang.ClassLoader.getPlatformClassLoader;
import static java.lang.System.getProperty;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.walk;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.pingidentity.opendst.api.Deployment.Image;
import com.pingidentity.opendst.api.Deployment.Service;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A deployment describes a set of services to run, each service running in its own class loader.
 * <p>
 * Each service is associated with an image that describes the war directory and the main class to run.
 * The war directory is expected to have a WEB-INF/fs directory that will be copied to the deployment
 * directory for the service.
 */
public final class Deployment {
    private static final Path WARS_DIR = Path.of(getProperty("opendst.wars-dir", "wars"));

    private final Map<String, Image> images = new HashMap<>();
    private final Map<String, Service> services;

    /**
     * Creates a new deployment with the specified images and services.
     *
     * @param images   the list of images to use
     * @param services the list of services to deploy
     * @return a new Deployment instance
     * @throws IOException if an I/O error occurs
     */
    public static Deployment deployment(List<Image> images, List<Service> services) throws IOException {
        return new Deployment(images, services);
    }

    private Deployment(List<Image> images, List<Service> services) throws IOException {
        for (var image : images) {
            var cp = classPath(WARS_DIR.resolve(image.warDir()).resolve("WEB-INF"));
            var extra = image.classPath();
            var combined = new URL[cp.length + extra.length];
            System.arraycopy(cp, 0, combined, 0, cp.length);
            System.arraycopy(extra, 0, combined, cp.length, extra.length);
            this.images.put(image.name(), new Image(image.name(), image.warDir(), image.mainClassName(), combined));
        }
        this.services = services.stream().collect(toMap(Service::name, identity()));
        this.services.values().forEach(s -> {
            if (!this.images.containsKey(s.imageName())) {
                throw new IllegalArgumentException("Image " + s.imageName() + " does not exist");
            }
        });
    }

    /**
     * Starts all services in the deployment by initializing their file systems and launching their main classes.
     *
     * @throws Exception if an error occurs during service startup
     */
    public void deploy() throws Exception {
        for (var service : services.values()) {
            var image = images.get(service.imageName());
            var serviceClassLoader = new URLClassLoader(service.name(), image.classPath(), getPlatformClassLoader());
            var mainMethod = serviceClassLoader.loadClass(image.mainClassName()).getMethod("main", String[].class);
            startNode(
                    service.name(),
                    service.ip(),
                    serviceClassLoader,
                    WARS_DIR.resolve(image.warDir()).resolve("WEB-INF/fs"),
                    mainMethod,
                    service.args());
        }
    }

    private static URL[] classPath(Path webInfDir) throws IOException {
        var urls = new ArrayList<URL>();
        try (var libJars = walk(webInfDir.resolve("lib")).sorted()) {
            var dependencies = libJars.filter(p -> p.toString().toLowerCase().endsWith(".jar") && isRegularFile(p));
            for (var it = dependencies.iterator(); it.hasNext(); ) {
                urls.add(it.next().toUri().toURL());
            }
        } catch (NoSuchFileException e) {
            // No dependency
        }
        var classesJar = webInfDir.resolve("classes.jar");
        if (exists(classesJar)) {
            urls.add(classesJar.toUri().toURL());
        }
        var classesDir = webInfDir.resolve("classes");
        if (exists(classesDir)) {
            urls.add(classesDir.toUri().toURL());
        }
        return urls.toArray(new URL[0]);
    }
}
