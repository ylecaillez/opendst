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
package com.pingidentity.opendst.testapp;

import static com.pingidentity.opendst.api.Deployment.Image.image;
import static com.pingidentity.opendst.api.Deployment.Service.service;
import static com.pingidentity.opendst.api.Simulator.deploy;
import static java.net.InetAddress.ofLiteral;
import static java.util.List.of;

import com.pingidentity.opendst.api.LogMonitor;
import java.nio.file.Path;

/**
 * A high-level integration DST that tests a multi-node deployment.
 * <p>
 * It uses the {@link com.pingidentity.opendst.api.Deployment} API to define a cluster of
 * {@link SecretSequenceApp.Server} and {@link SecretSequenceApp.Client} nodes and
 * verifies their interaction under simulation.
 */
public class SecretSequenceDST implements LogMonitor {
    public static final Path WAR = Path.of("opendst-testapp");

    public void run() throws Exception {
        deploy(
                of(image("server-image", WAR, "com.pingidentity.opendst.testapp.SecretSequenceApp$Server"),
                   image("client-image", WAR, "com.pingidentity.opendst.testapp.SecretSequenceApp$Client")),
                of(service("server", "server-image", ofLiteral("10.0.0.2"), new String[]{ "8080"}),
                   service("client", "client-image", ofLiteral("10.0.0.3"), new String[]{"10.0.0.2", "8080"})));
    }

    @Override
    public void process(Log log) throws Throwable {
        if (log.message().contains("Bug reached!")) {
            throw new AssertionError("OpenDST: bug-discovered");
        }
    }
}
