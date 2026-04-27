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
package com.pingidentity.opendst.runner;

import java.io.File;
import java.nio.file.Path;

/**
 * Launch configuration for a child simulation JVM.
 *
 * <p>Built once per run by the parent {@link BuildRunner} and reused for every
 * fork spawned by {@link TestExecutor}.
 *
 * @param instrumentedAppsDir directory containing instrumented application JARs
 *     ({@code deployment/apps/*})
 * @param agentJarPath        absolute path to {@code opendst-agent.jar} (passed via {@code -javaagent})
 * @param patchModuleJarPath  absolute path to {@code opendst-patch.jar} (passed via {@code --patch-module})
 * @param jvmArguments        merged build-time defaults + CLI {@code --extra-jvm-args}, may be {@code null}
 * @param debugArgs           JDWP options when {@code --debug} is set, otherwise {@code null}
 * @param logSpy              optional fixed log spy file (if {@code null}, a per-run {@code simulator.log} is used)
 * @param mainClass           fully-qualified class name of the child JVM entry point
 *     (currently {@link OpenDSTExecutor})
 */
record JvmConfig(
        Path instrumentedAppsDir,
        String agentJarPath,
        String patchModuleJarPath,
        String jvmArguments,
        String debugArgs,
        File logSpy,
        String mainClass) {}
