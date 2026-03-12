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

import com.pingidentity.opendst.TraceEvents;
import com.pingidentity.opendst.api.TraceAuditor;
import generatedOutput.pobserve.PMachines;
import pobserve.runtime.Monitor;
import pobserve.runtime.events.PEvent;

import java.util.List;

/**
 * Trace auditor that validates TCP socket semantics using
 * PObserve-generated monitors compiled from the P formal
 * specification.
 *
 * <p>Trace events are emitted automatically by the simulated
 * network layer ({@code NodeSocketImpl}) and validated here
 * against all 6 P spec monitors:
 *
 * <ul>
 *   <li><b>Safety:</b> DataIntegrity, NoWriteAfterClose,
 *       NoPhantomData, IOExceptionOnClosedSocket</li>
 *   <li><b>Liveness:</b> DeliveryLiveness, EOFLiveness</li>
 * </ul>
 *
 * <p>Safety violations throw immediately (unchecked
 * {@code PAssertionFailureException} from the monitor).
 * Liveness is checked when the {@code TestCompleted} marker
 * event is received — any monitor stuck in a hot state is
 * a liveness violation.
 */
public final class NetworkFaultTraceAuditor
        implements TraceAuditor {

    private final List<Monitor<?>> monitors = List.of(
            new PMachines.DataIntegrity.Supplier().get(),
            new PMachines.NoWriteAfterClose.Supplier().get(),
            new PMachines.NoPhantomData.Supplier().get(),
            new PMachines.IOExceptionOnClosedSocket
                    .Supplier().get(),
            new PMachines.DeliveryLiveness.Supplier().get(),
            new PMachines.EOFLiveness.Supplier().get());

    private final TraceEventParser parser =
            new TraceEventParser();

    @Override
    public void process(Log log) throws Throwable {
        TraceEvents.TraceEvent event =
                TraceEvents.parse(log.message());
        if (event == null) return;

        if (event instanceof TraceEvents.TestCompleted) {
            checkLiveness();
            return;
        }

        PEvent<?> pEvent = parser.toPObserveEvent(event);
        if (pEvent == null) return;

        // Dispatch to every monitor that observes this event
        // type. Safety violations propagate immediately as
        // unchecked PAssertionFailureException.
        for (Monitor<?> monitor : monitors) {
            if (monitor.getEventTypes()
                    .contains(pEvent.getClass())) {
                monitor.accept(pEvent);
            }
        }
    }

    /**
     * Checks liveness monitors for hot-state violations.
     *
     * <p>PObserve liveness monitors use hot/cold states. A
     * monitor stuck in a hot state at test end means a
     * liveness property was violated (e.g., data was written
     * but never delivered). Since the {@code State.Temperature}
     * enum has no public getter, we check the state name
     * against the known hot-state names from the P spec.
     */
    private void checkLiveness() {
        for (Monitor<?> monitor : monitors) {
            String state = monitor.getCurrentState().name();
            if ("PendingDelivery".equals(state)
                    || "PendingEOFDelivery".equals(state)) {
                throw new AssertionError(
                        "Liveness violation: "
                        + monitor.getClass().getSimpleName()
                        + " stuck in hot state " + state);
            }
        }
    }
}
