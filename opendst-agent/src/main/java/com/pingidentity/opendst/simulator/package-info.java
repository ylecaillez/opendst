/*
 * Copyright 2024-2026 Ping Identity Corporation
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

/**
 * Deterministic simulation engine.
 *
 * <p>The classes in this package own simulator state and lifecycle:
 * <ul>
 *   <li>{@link com.pingidentity.opendst.simulator.Simulator} drives the deterministic
 *       scheduler, plan execution, and global hash chain.</li>
 *   <li>{@link com.pingidentity.opendst.simulator.Node} represents a single simulated
 *       service host and exposes the public surface that JDK interceptors call into
 *       (e.g. {@code currentNodeOrNull}, {@code attachThread}, {@code scheduleNow}).</li>
 *   <li>{@link com.pingidentity.opendst.simulator.SimulationContext} bundles the
 *       per-simulation services (scheduler, RNG, network registry, fault injector,
 *       logger) shared with each {@code Node}.</li>
 *   <li>{@link com.pingidentity.opendst.simulator.FaultInjector} applies fault plans
 *       at deterministic decision points.</li>
 *   <li>{@link com.pingidentity.opendst.simulator.NodeSocketImpl} is the per-node
 *       socket implementation injected by {@code NetworkInterceptors}.</li>
 *   <li>{@link com.pingidentity.opendst.simulator.ConsoleCapture} routes signal records
 *       to stdout for the runner to consume.</li>
 * </ul>
 *
 * <p>This package depends on {@link com.pingidentity.opendst.intercept} for JDK-internals
 * plumbing (e.g. {@code ThreadsInterceptors.Internals} for poking virtual-thread state
 * and {@code VirtualThreadUnblocker} for releasing simulator-backed threads). The reverse
 * dependency exists too: interceptors call {@code Node.currentNodeOrNull()} to dispatch
 * advice into the right node. Both packages are siblings within the agent and form a
 * deliberate two-way collaboration.
 */
package com.pingidentity.opendst.simulator;
