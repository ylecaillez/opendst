package com.pingidentity.opendst.runner;

import static com.pingidentity.opendst.runner.Commons.JSON_MAPPER;
import static com.pingidentity.opendst.runner.ExecutionResult.TrackedAssertion.newFailAssertion;
import static com.pingidentity.opendst.runner.ExecutionResult.TrackedAssertion.newPassAssertion;

import java.util.HashMap;
import java.util.Map;

import com.pingidentity.opendst.runner.Commons.SignalEvent;
import com.pingidentity.opendst.runner.Signal.AssertSignal;
import com.pingidentity.opendst.runner.Signal.LifecycleSignal;
import tools.jackson.databind.JsonNode;

final class ExecutionResult {

    record TrackedAssertion(String name,
                            int passCount,
                            long firstPassIteration,
                            JsonNode firstPassDetails,
                            int failCount,
                            long firstFailIteration,
                            JsonNode firstFailDetails) {

        static TrackedAssertion newPassAssertion(String name, long iteration, JsonNode details) {
            return new TrackedAssertion(name, 1, iteration, details, 0, -1, null);
        }

        static TrackedAssertion newFailAssertion(String name, long iteration, JsonNode details) {
            return new TrackedAssertion(name, 0, -1, null, 1, iteration, details);
        }

        TrackedAssertion pass() {
            return new TrackedAssertion(name, passCount + 1, firstPassIteration, firstPassDetails, failCount,
                                        firstFailIteration, firstFailDetails);
        }

        TrackedAssertion fail() {
            return new TrackedAssertion(name, passCount, firstPassIteration, firstPassDetails, failCount + 1,
                                        firstFailIteration, firstFailDetails);
        }
    };

    private final Map<String, TrackedAssertion> assertionsHit = new HashMap<>();
    private boolean interesting;
    private int runHash;

    int runHash() {
        return runHash;
    }

    public boolean isInteresting() {
        return interesting;
    }

    Map<String, TrackedAssertion> assertionsHit() {
        return assertionsHit;
    }

    /** Returns {@code true} if the simulation stopped with a non-success reason. */
    boolean runFailed() {
        var hit = assertionsHit.get("simulation stopped successfully");
        return hit != null && hit.failCount() > 0;
    }

    boolean addSignal(SignalEvent signal, boolean isInteresting) {
        interesting |= isInteresting;
        if (signal.signal() instanceof LifecycleSignal lifecycleSignal) {
            if ("started".equals(lifecycleSignal.message())) {
                trackAssertion("simulation started", true, signal.iteration(), null);
            } else if ("stopped".equals(lifecycleSignal.message())) {
                boolean success = "success".equals(lifecycleSignal.reason());
                trackAssertion("simulation stopped successfully", success,
                               signal.iteration(), stoppedDetails(lifecycleSignal));
                runHash = lifecycleSignal.hash();
                return true;
            }
        } else if (signal.signal() instanceof AssertSignal assertSignal) {
            trackAssertion(assertSignal.message(), assertSignal.condition(),
                           signal.iteration(), assertSignal.details());
        }
        return false;
    }

    /**
     * Builds a details {@link JsonNode} for the {@code lifecycle/stopped} signal.
     * Includes the exit reason and, when present, the cause message.
     */
    private static JsonNode stoppedDetails(LifecycleSignal signal) {
        var node = JSON_MAPPER.createObjectNode();
        node.put("reason", signal.reason());
        if (signal.cause() != null) {
            node.put("cause", signal.cause());
        }
        return node;
    }

    private void trackAssertion(String name, boolean pass, long iteration, JsonNode details) {
        assertionsHit.compute(name, (_, existing) -> existing == null
                ? pass ? newPassAssertion(name, iteration, details)
                       : newFailAssertion(name, iteration, details)
                : pass ? existing.pass() : existing.fail());
    }
}
