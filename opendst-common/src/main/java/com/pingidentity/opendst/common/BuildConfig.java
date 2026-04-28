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

/**
 * Build-time configuration baked into the self-contained JAR as
 * {@code META-INF/opendst/build-config.json}.
 *
 * <p>Carries the JVM argument string assembled by the Maven plugin and the
 * default fault configuration applied to every run unless overridden by a
 * {@link Plan}.
 */
public record BuildConfig(String jvmArguments, Faults.Config faults) {}
