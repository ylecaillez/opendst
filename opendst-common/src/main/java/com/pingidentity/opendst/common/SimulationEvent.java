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
package com.pingidentity.opendst.common;

import java.time.Instant;

/**
 * Wire envelope for one structured event emitted by the child JVM and consumed by the parent.
 *
 * <p>Each line of the child JVM's JSON stdout encodes exactly one {@code SimulationEvent}. The
 * envelope carries cross-cutting metadata (line id, source, originating virtual host, simulator
 * iteration, simulator instant) wrapped around the typed {@link Signal} payload.
 *
 * <p>Component names match the wire format directly ({@code lid}, {@code it}, {@code at},
 * {@code signal}) so that default {@code jackson-jr} record binding round-trips without extra
 * annotations. The polymorphic {@link #signal} field requires a custom value reader/writer pair on
 * each side (registered in the agent and the runner respectively) to handle the {@code type}
 * discriminator inside the inner {@code signal} object.
 *
 * <p>{@code vhost} is {@code null} for simulator-framework signals (lifecycle, internal errors)
 * and set for vhost-attributable ones (asserts, guidance, console, uncaught exceptions, platform
 * thread diagnostics).
 *
 * @param lid    monotonic line id assigned by the agent; useful for ordering and dedup
 * @param source {@code "simulator"} for framework-internal events, {@code "vhost"} otherwise
 * @param vhost  the originating virtual host, or {@code null} for framework events
 * @param it     simulator iteration counter at the time of emission
 * @param at     simulator instant at the time of emission (ISO-8601 on the wire)
 * @param signal the typed {@link Signal} payload (serialized as the {@code log} object)
 */
public record SimulationEvent(long lid, String source, String vhost, long it, Instant at, Signal signal) {}
