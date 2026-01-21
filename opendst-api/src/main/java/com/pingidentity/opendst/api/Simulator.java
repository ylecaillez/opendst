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
package com.pingidentity.opendst.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Entry point for controlling the simulation from within a DST scenario.
 *
 * <p>All methods in this class are <b>stubs</b> that throw {@link IllegalStateException} at
 * compile time. At runtime, the OpenDST agent rewrites every call site to delegate to the
 * engine implementation in {@code com.pingidentity.opendst.Simulator}.
 *
 * <p>Users should only depend on the {@code opendst-api} artifact; the engine is loaded
 * automatically by the Maven plugin.
 */
public final class Simulator {

    private Simulator() {}

    /**
     * Starts a new simulated node with the given hostname and IP address.
     *
     * <p>The bootstrap callable runs as the node's main logic. The node is isolated with its own
     * virtual network identity and filesystem working directory.
     *
     * @param hostName  the hostname for the new node
     * @param ipAddress the IP address for the new node
     * @param bootstrap the callable to execute as the node's main logic
     * @throws IOException if the node cannot be started
     */
    public static void startNode(String hostName, String ipAddress, Callable<Void> bootstrap) throws IOException {
        throw new IllegalStateException(
                "Simulator.startNode() is a stub — the OpenDST agent must be active to rewrite this call");
    }

    /**
     * Creates and starts a deployment of services within the simulation.
     *
     * @param images   the list of images to use
     * @param services the list of services to deploy
     * @throws Exception if an error occurs during deployment
     */
    public static void deploy(List<Deployment.Image> images, List<Deployment.Service> services) throws Exception {
        throw new IllegalStateException(
                "Simulator.deploy() is a stub — the OpenDST agent must be active to rewrite this call");
    }

    /**
     * Returns the working directory for the current simulated node.
     *
     * @return the path to the node's working directory
     */
    public static Path workingDirectory() {
        throw new IllegalStateException(
                "Simulator.workingDirectory() is a stub — the OpenDST agent must be active to rewrite this call");
    }
}
