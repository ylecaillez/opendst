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
 * OpenDST agent module — root package.
 *
 * <p>The agent code is split across three subpackages:
 * <ul>
 *   <li>{@link com.pingidentity.opendst.simulator} — the deterministic simulation engine
 *       ({@code Simulator}, {@code Node}, {@code SimulationContext}, {@code FaultInjector},
 *       {@code NodeSocketImpl}, {@code ConsoleCapture}).</li>
 *   <li>{@link com.pingidentity.opendst.intercept} — JDK interception advice and the
 *       {@code SimulatorAgent} premain that installs it. Rewrites time, randomness,
 *       threading, and networking calls to route through the engine.</li>
 *   <li>{@link com.pingidentity.opendst.sdk} — bytecode-rewrite targets for the
 *       {@code com.pingidentity.opendst.sdk.Signals}/{@code Assert} SDK calls that the
 *       Maven plugin redirects at workload build time.</li>
 * </ul>
 *
 * <p>{@link com.pingidentity.opendst.SimulationLauncher} stays at the root because it is
 * referenced by the runner via fully-qualified-name string.
 */
package com.pingidentity.opendst;
