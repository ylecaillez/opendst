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

import static com.pingidentity.opendst.Constants.APPS_DIR_PROPERTY;
import static com.pingidentity.opendst.Simulator.startNode;
import static java.lang.ClassLoader.getPlatformClassLoader;
import static java.lang.System.getProperty;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.walk;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.net.InetAddress;
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
 */
public final class Deployment {
    private static final Path APPS_DIR = Path.of(getProperty(APPS_DIR_PROPERTY, "apps"));

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
            var cp = classPath(APPS_DIR.resolve(image.warDir()).resolve("WEB-INF"));
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
     * Starts all services in the deployment by launching their main classes.
     *
     * @throws Exception if an error occurs during service startup
     */
    public void deploy() throws Exception {
        for (var service : services.values()) {
            var image = images.get(service.imageName());
            var serviceClassLoader = new URLClassLoader(service.name(), image.classPath(), getPlatformClassLoader());
            var mainMethod = serviceClassLoader.loadClass(image.mainClassName()).getMethod("main", String[].class);
            startNode(service.name(), service.ip(), serviceClassLoader, mainMethod, service.args());
        }
    }

    /**
     * Represents an image for a service, including its name, war directory, main class, and classpath.
     *
     * @param name          the name of the image
     * @param warDir        the path to the war directory
     * @param mainClassName the fully qualified name of the main class
     * @param classPath     the classpath URLs for the image
     */
    public record Image(String name, Path warDir, String mainClassName, URL[] classPath) {
        /**
         * Creates a new image with the specified name, war directory, and main class name.
         *
         * @param name          the name of the image
         * @param warDir        the path to the war directory
         * @param mainClassName the fully qualified name of the main class
         * @return a new Image instance
         */
        public static Image image(String name, Path warDir, String mainClassName) {
            return new Image(name, warDir, mainClassName, new URL[0]);
        }

        /**
         * Creates a new image with the specified name, war directory, main class name and extra classpath.
         *
         * @param name           the name of the image
         * @param warDir         the path to the war directory
         * @param mainClassName  the fully qualified name of the main class
         * @param extraClassPath the extra classpath URLs
         * @return a new Image instance
         */
        public static Image image(String name, Path warDir, String mainClassName, URL[] extraClassPath) {
            return new Image(name, warDir, mainClassName, extraClassPath);
        }
    }

    /**
     * Represents a service to be deployed, including its name, image name, IP address, and arguments.
     *
     * @param name      the name of the service
     * @param imageName the name of the image to use
     * @param ip        the IP address for the service
     * @param args      the arguments to pass to the service
     */
    public record Service(String name, String imageName, InetAddress ip, String[] args) {
        /**
         * Creates a new service with the specified name, image name, and IP address.
         *
         * @param name      the name of the service
         * @param imageName the name of the image to use
         * @param ip        the IP address for the service
         * @return a new Service instance
         */
        public static Service service(String name, String imageName, InetAddress ip) {
            return service(name, imageName, ip, new String[0]);
        }

        /**
         * Creates a new service with the specified name, image name, IP address, and arguments.
         *
         * @param name      the name of the service
         * @param imageName the name of the image to use
         * @param ip        the IP address for the service
         * @param args      the arguments to pass to the service
         * @return a new Service instance
         */
        public static Service service(String name, String imageName, InetAddress ip, String[] args) {
            return new Service(name, imageName, ip, args);
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
