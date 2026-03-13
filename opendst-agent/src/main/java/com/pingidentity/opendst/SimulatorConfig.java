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
package com.pingidentity.opendst;

/**
 * Global configuration and resource limits for the OpenDST simulator.
 */
public record SimulatorConfig(
        int maxNodes,
        int maxAddresses,
        int maxTasks,
        int maxVirtualThreadsPerNode
) {
    /** Default configuration with sane limits. */
    public static final SimulatorConfig DEFAULT = new SimulatorConfig(
            100,    // maxNodes
            256,    // maxAddresses
            10_000, // maxTasks
            1000    // maxVirtualThreadsPerNode
    );

    /**
     * Creates a configuration by reading system properties, falling back to defaults.
     */
    public static SimulatorConfig fromSystemProperties() {
        return new SimulatorConfig(
                Integer.getInteger("opendst.maxNodes", DEFAULT.maxNodes),
                Integer.getInteger("opendst.maxAddresses", DEFAULT.maxAddresses),
                Integer.getInteger("opendst.maxTasks", DEFAULT.maxTasks),
                Integer.getInteger("opendst.maxVirtualThreadsPerNode", DEFAULT.maxVirtualThreadsPerNode)
        );
    }
}
