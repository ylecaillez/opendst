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

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Data model for the YAML deployment descriptor used by the {@code build} mojo.
 *
 * <p>The descriptor defines the deployment topology using a docker-compose-like format:
 * services (as a named map) and an optional trace auditor. Each service carries its own
 * source and class declaration. Orchestration concerns (faults, duration, fork count)
 * are configured separately in {@link BuildConfig} and via CLI arguments.
 *
 * <p>Example YAML:
 * <pre>{@code
 * services:
 *   server:
 *     class: com.example.App$Server
 *     ip: 10.0.0.1
 *     args: ["8080"]
 *   client:
 *     class: com.example.App$Client
 *     ip: 10.0.0.2
 *     args: ["10.0.0.1", "8080"]
 *
 * traceAuditor:
 *   class: com.example.MyTraceAuditor
 * }</pre>
 */
public record DeploymentDescriptor(Map<String, ServiceDescriptor> services, TraceAuditorDescriptor traceAuditor) {

    /**
     * Describes a service with three mutually exclusive source modes:
     * <ol>
     *   <li>{@code artifact} — a Maven GAV coordinate resolved from Maven repositories</li>
     *   <li>{@code dir} — a local directory path relative to the project basedir</li>
     *   <li>Neither — the current project's {@code target/classes/} and runtime dependencies</li>
     * </ol>
     *
     * <p>At build time, the plugin enriches every service with a {@code dir} value pointing
     * to the actual {@code apps/} subdirectory name in the output JAR. The baked descriptor
     * therefore always has {@code dir} set on every service.
     */
    public record ServiceDescriptor(
            String artifact,
            String dir,
            @JsonProperty("class") String className,
            String ip,
            List<String> args) {

        /**
         * Returns the {@code apps/} subdirectory name for this service's source.
         *
         * <ul>
         *   <li>{@code dir} set — last path component (e.g., {@code target/apps/foo} &rarr; {@code foo})</li>
         *   <li>{@code artifact} set — {@code artifactId-version} parsed from the GAV coordinate</li>
         *   <li>Neither — falls back to the given {@code serviceName}</li>
         * </ul>
         *
         * @param serviceName the service name (map key), used as fallback
         */
        public String appDir(String serviceName) {
            if (dir != null && !dir.isBlank()) {
                return Path.of(dir).getFileName().toString();
            }
            if (artifact != null && !artifact.isBlank()) {
                var parts = artifact.split(":");
                if (parts.length < 2) {
                    throw new IllegalArgumentException(
                            "Malformed artifact coordinate (expected groupId:artifactId[:...]:version): " + artifact);
                }
                return parts[1] + "-" + parts[parts.length - 1];
            }
            return serviceName;
        }

        public String[] argsArray() {
            return args == null ? new String[0] : args.toArray(String[]::new);
        }
    }

    /**
     * Trace auditor configuration with the same source model as services.
     *
     * <p>The trace auditor class is loaded from the source identified by {@code artifact},
     * {@code dir}, or the current project (if neither is set). This makes the trace auditor
     * self-describing rather than referencing a service for its classloader.
     */
    public record TraceAuditorDescriptor(
            String artifact,
            String dir,
            @JsonProperty("class") String className) {

        /**
         * Returns the {@code apps/} subdirectory name for the trace auditor's source.
         *
         * <ul>
         *   <li>{@code dir} set — last path component</li>
         *   <li>{@code artifact} set — {@code artifactId-version}</li>
         *   <li>Neither — returns {@code null} (current project; plugin must enrich before baking)</li>
         * </ul>
         */
        public String appDir() {
            if (dir != null && !dir.isBlank()) {
                return Path.of(dir).getFileName().toString();
            }
            if (artifact != null && !artifact.isBlank()) {
                var parts = artifact.split(":");
                if (parts.length < 2) {
                    throw new IllegalArgumentException(
                            "Malformed artifact coordinate (expected groupId:artifactId[:...]:version): " + artifact);
                }
                return parts[1] + "-" + parts[parts.length - 1];
            }
            return null;
        }
    }
}
