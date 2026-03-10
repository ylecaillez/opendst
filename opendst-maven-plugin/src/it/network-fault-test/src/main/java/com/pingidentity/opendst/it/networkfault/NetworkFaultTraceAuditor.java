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
package com.pingidentity.opendst.it.networkfault;

import com.pingidentity.opendst.api.TraceAuditor;
import com.pingidentity.opendst.it.networkfault.SocketStateMachine.Tracker;

/**
 * Trace auditor that validates TCP socket state transitions from outside the simulation.
 *
 * <p>Parses structured {@code [event]} lines emitted by {@link NetworkFaultApp} and
 * feeds them through an independent {@link Tracker} instance. Any illegal state
 * transition throws an {@link AssertionError}, which the OpenDST runner interprets
 * as a simulation failure.
 */
public final class NetworkFaultTraceAuditor implements TraceAuditor {

    private final Tracker monitor = new Tracker();

    @Override
    public void process(Log log) throws Throwable {
        String msg = log.message();
        if (!msg.startsWith(Tracker.EVENT_PREFIX)) return;

        String payload = msg.substring(Tracker.EVENT_PREFIX.length());
        int space = payload.indexOf(' ');
        try {
            monitor.applyTransition(payload.substring(0, space), payload.substring(space + 1));
        } catch (IllegalStateException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }
}
