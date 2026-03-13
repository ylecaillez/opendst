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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NetworkTest {

    @Test
    void testPartition() throws Exception {
        var simulator = mock(Simulator.class);
        var network = new Network(simulator);
        var nodeA = mock(Node.class);
        var nodeB = mock(Node.class);
        var ipA = InetAddress.ofLiteral("10.0.0.1");
        var ipB = InetAddress.ofLiteral("10.0.0.2");

        when(nodeA.inetAddresses()).thenReturn(Stream.of(ipA));
        when(nodeB.inetAddresses()).thenReturn(Stream.of(ipB));

        network.registerDns("nodeA", nodeA);
        network.registerDns("nodeB", nodeB);

        // Basic connectivity
        assertDoesNotThrow(() -> network.route(ipA, ipB, 80));

        // Partition
        network.partition("nodeA", "nodeB");
        assertThrows(NoRouteToHostException.class, () -> network.route(ipA, ipB, 80));

        // Self-reachability is always true
        assertDoesNotThrow(() -> network.route(ipA, ipA, 80));

        // Healing
        network.heal("nodeA", "nodeB");
        assertDoesNotThrow(() -> network.route(ipA, ipB, 80));
    }
}
