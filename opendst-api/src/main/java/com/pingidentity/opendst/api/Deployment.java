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
package com.pingidentity.opendst.api;

import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Path;

/**
 * Deployment configuration types for multi-node simulations.
 *
 * <p>These are pure data types (records) used to describe a deployment topology.
 * They are passed through to the engine unchanged — no bytecode rewriting is needed.
 */
public final class Deployment {

    private Deployment() {}

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
}
