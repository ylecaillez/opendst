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
 * JDK interception layer.
 *
 * <p>This package contains the bytecode advice that redirects non-deterministic JDK APIs (time, randomness, threads,
 * networking, system services) to the simulator engine in {@link com.pingidentity.opendst.simulator}, plus the Java
 * agent that installs them.
 *
 * <ul>
 *   <li>{@link com.pingidentity.opendst.intercept.SimulatorAgent}: {@code premain} and {@code agentmain} entry
 *   points; wires every {@code *Interceptors.instrument(...)} chain into a single ByteBuddy {@code AgentBuilder}.</li>
 *   <li>{@link com.pingidentity.opendst.intercept.Intercepts}: annotation that documents which JDK member each
 *   advice class targets; consumed by the audit test and the generated instrumentation report.</li>
 *   <li>{@link com.pingidentity.opendst.intercept.TimeInterceptors},
 *       {@link com.pingidentity.opendst.intercept.RandomInterceptors},
 *       {@link com.pingidentity.opendst.intercept.ThreadsInterceptors},
 *       {@link com.pingidentity.opendst.intercept.NetworkInterceptors},
 *       {@link com.pingidentity.opendst.intercept.SystemInterceptors}: one class per JDK domain, each containing the
 *       advice classes and an {@code instrument(AgentBuilder)}
 *       method.</li>
 * </ul>
 *
 * <p>Each advice class dispatches by calling {@code Node.currentNodeOrNull()} (from
 * {@link com.pingidentity.opendst.simulator.Node}); when the calling thread is bound to a simulator node, the advice
 * routes the operation through the engine; otherwise the original JDK behaviour runs untouched.
 */
package com.pingidentity.opendst.intercept;
