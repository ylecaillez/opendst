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
package com.pingidentity.opendst.it.nested;

import com.pingidentity.opendst.sdk.Assert;
import com.pingidentity.opendst.simulator.Simulator;
import java.io.PrintStream;

/**
 * Regression test for {@code Node#execute()} thread-local restore symmetry.
 *
 * <p>When user code calls {@link Simulator#startNode(String, String, java.util.concurrent.Callable)}
 * from inside another node's running scenario, the call is dispatched re-entrantly on the
 * same carrier thread:
 *
 * <pre>
 *   carrier T:  Node.execute(parent)         setOut(parent.console)
 *                 parent-main runs:
 *                   Simulator.startNode("worker", ...)
 *                     Node.execute(worker)   setOut(worker.console)
 *                       (body just spawns worker-main VT and returns)
 *                     finally: restores CURRENT_NODE=parent
 *                              but NOT System.out — still worker.console !
 *                   parent-main resumes here ← System.out is wrong
 * </pre>
 *
 * <p>This test captures {@code System.out} before the nested call and asserts it is
 * unchanged afterwards. With the asymmetric restore, the assertion fires.
 */
public final class NestedStartNodeApp {

    public static void main(String[] args) throws Exception {
        // System.out at this point is the parent node's structured-logging PrintStream.
        PrintStream parentOut = System.out;
        PrintStream parentErr = System.err;

        Assert.reachable("parent-main-started");

        // Spawn a child node from inside parent's scenario — exercising the re-entrant
        // Node.execute(...) path on the same carrier thread.
        Simulator.startNode("worker", "10.0.0.2", () -> {
            Assert.reachable("worker-scenario-started");
            // The worker's console must be active here (it is — that's not the bug).
            // We don't assert on it because comparing PrintStream identities inside the
            // worker scenario is testing the inner-loop behaviour, not the restore.
            return null;
        });

        // After the nested startNode returns, System.out / System.err on this thread
        // must be exactly what they were before. Without the fix, they still point at
        // the worker node's console and any subsequent println on parent is mis-tagged.
        Assert.alwaysOrUnreachable(
                System.out == parentOut, "System.out preserved across nested Simulator.startNode");
        Assert.alwaysOrUnreachable(
                System.err == parentErr, "System.err preserved across nested Simulator.startNode");

        Assert.reachable("parent-main-resumed-after-nested-startnode");
    }
}
