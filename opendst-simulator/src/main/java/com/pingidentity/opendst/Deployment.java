/*
 * Copyright 2025 Ping Identity Corporation
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
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.walk;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * A deployment describes a set of services to run, each service running in its own class loader.
 * <p>
 * Each service is associated with an image that describes the war directory and the main class to run.
 * The war directory is expected to have a WEB-INF/fs directory that will be copied to the deployment
 * directory for the service.
 */
public final class Deployment implements Callable<Void> {
    private static final Path DEPLOYMENT_DIR = Path.of("target/deployment");
    private static final Path WARS_DIR = Path.of("target/wars");

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
            return new Image(name, warDir, mainClassName, null);
        }

        private Image withClassPath(URL[] urls) {
            return new Image(name, warDir, mainClassName, urls);
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
        DEPLOYMENT_DIR.toFile().mkdirs();
        for (var image : images) {
            this.images.put(image.name(),
                            image.withClassPath(classPath(WARS_DIR.resolve(image.warDir()).resolve("WEB-INF"))));
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
     * @return Always null
     * @throws Exception if an error occurs during service startup
     */
    @Override
    public Void call() throws Exception {
        for (var service : services.values()) {
            var image = images.get(service.imageName());
            var serviceClassLoader = new URLClassLoader(service.name(), image.classPath(), getSystemClassLoader());
            var mainMethod = serviceClassLoader.loadClass(image.mainClassName()).getMethod("main", String[].class);
            startNode(service.name(), service.ip(),
                      serviceClassLoader, WARS_DIR.resolve(image.warDir()).resolve("WEB-INF/fs"),
                      mainMethod, service.args());
        }
        return null;
    }


    private static URL[] classPath(Path webInfDir) throws IOException {
        try (var libJars = walk(webInfDir.resolve("lib")).sorted()) {
            var classPath = Stream.concat(Stream.of(webInfDir.resolve("classes")),
                                          libJars.filter(p -> p.toString().toLowerCase().endsWith(".jar")
                                                  && isRegularFile(p)));
            var urls = new ArrayList<URL>();
            for (var it = classPath.iterator(); it.hasNext();) {
                urls.add(it.next().toUri().toURL());
            }
            return urls.toArray(new URL[0]);
        }
    }
}
