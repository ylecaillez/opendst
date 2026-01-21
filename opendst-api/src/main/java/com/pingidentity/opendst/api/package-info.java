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

/**
 * Public API for OpenDST (Deterministic Simulation Testing).
 *
 * <p>This package contains all types a user needs to write deterministic simulation tests:
 * <ul>
 *   <li>{@link com.pingidentity.opendst.api.Assert} — property assertions (invariants and liveness)</li>
 *   <li>{@link com.pingidentity.opendst.api.Signals} — simulation lifecycle signals</li>
 *   <li>{@link com.pingidentity.opendst.api.Simulator} — node management and deployment</li>
 *   <li>{@link com.pingidentity.opendst.api.Deployment} — deployment topology records</li>
 *   <li>{@link com.pingidentity.opendst.api.LogMonitor} — real-time log observation</li>
 * </ul>
 *
 * <p>At compile time, users depend only on this module ({@code opendst-api}). At runtime, the
 * OpenDST Maven plugin injects the engine ({@code opendst-core}) and a Java agent that rewrites
 * stub call sites to their engine implementations.
 */
package com.pingidentity.opendst.api;
