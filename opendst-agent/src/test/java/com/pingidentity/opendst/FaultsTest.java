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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.SocketException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FaultsTest {

    @Test
    void testResetFault() {
        var simulator = mock(Simulator.class);
        when(simulator.isReady()).thenReturn(true);
        
        // Probability 1.0 ensures it always triggers
        var config = new Faults.Config(new Faults.Config.NetworkConfig(
                true, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, Duration.ZERO, 1.0, 0));
        var injector = new Faults.Injector(simulator, config);

        assertThrows(SocketException.class, injector::onNetworkSend);
        assertThrows(SocketException.class, injector::onNetworkReceive);
    }

    @Test
    void testTimeoutFault() {
        var simulator = mock(Simulator.class);
        when(simulator.isReady()).thenReturn(true);
        
        // Probability 1.0 ensures it always triggers
        var config = new Faults.Config(new Faults.Config.NetworkConfig(
                true, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, Duration.ZERO, 0, 1.0));
        var injector = new Faults.Injector(simulator, config);

        assertNotEquals(Duration.ZERO, injector.onNetworkTimeout());
    }

    @Test
    void testFaultDisabled() {
        var simulator = mock(Simulator.class);
        when(simulator.isReady()).thenReturn(true);
        
        // Enabled = false
        var config = new Faults.Config(new Faults.Config.NetworkConfig(
                false, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0, Duration.ZERO, 1.0, 1.0));
        var injector = new Faults.Injector(simulator, config);

        assertDoesNotThrow(injector::onNetworkSend);
        assertDoesNotThrow(injector::onNetworkReceive);
        assertDoesNotThrow(injector::onNetworkTimeout);
    }
}
