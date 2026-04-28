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
 * Runner runtime and child JVM entry point for OpenDST.
 *
 * <p>This package runs in both JVM processes:
 * <ul>
 *   <li><b>Parent process</b> -- {@link com.pingidentity.opendst.runner.Bootstrap} extracts the
 *       self-contained JAR and reflectively invokes
 *       {@link com.pingidentity.opendst.runner.RunnerCli}, which drives the CLI, spawns and
 *       monitors child JVMs, and feeds them execution plans produced by a
 *       {@link com.pingidentity.opendst.runner.Planner}.</li>
 *   <li><b>Child process</b> -- {@link com.pingidentity.opendst.runner.SimulationLauncher} parses
 *       the deployment descriptor, creates classloader-isolated nodes per service, and hands off
 *       to {@link com.pingidentity.opendst.simulator.Simulator}.</li>
 * </ul>
 *
 * <p>{@code SimulationLauncher} lives here (rather than in {@code opendst-agent}) because it needs
 * Jackson databind + YAML to parse {@code deployment.yaml}. The agent is a shaded JAR with only
 * jackson-jr.
 *
 * @see <a href="../../../../../../../../../../DESIGN.md">DESIGN.md</a> for the full execution
 *      architecture.
 */
package com.pingidentity.opendst.runner;
