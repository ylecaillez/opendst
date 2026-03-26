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

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;
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

    /** Injectable-values key used to pass the Maven project's artifactId into deserialization. */
    public static final String PROJECT_ARTIFACT_ID_KEY = "projectArtifactId";

    /**
     * Typed representation of a service's source, replacing the stringly-typed
     * {@code artifact}/{@code dir}/{@code scope} YAML fields.
     *
     * <p>Used by the build plugin for exhaustive {@code switch}-based dispatch
     * (enrichment, artifact resolution, instrumentation) without if/else chains.
     *
     * <p>After enrichment, all sources become {@link Dir} — the baked descriptor
     * in the output JAR always has {@code dir} set on every service.
     */
    public sealed interface Source {

        /** Returns the {@code apps/} subdirectory name for this source. */
        String appDir();

        /**
         * Constructs the appropriate {@link Source} variant from flat YAML fields.
         *
         * <p>The three fields are mutually exclusive:
         * <ul>
         *   <li>{@code artifact} set — {@link Artifact}</li>
         *   <li>{@code dir} set — {@link Dir}</li>
         *   <li>Neither — {@link Project} (current Maven project, controlled by {@code scope})</li>
         * </ul>
         *
         * @param artifact           Maven GAV coordinate, or {@code null}
         * @param dir                local directory path, or {@code null}
         * @param scope              {@code "compile"} or {@code "test"}, or {@code null} (defaults to compile)
         * @param projectArtifactId  the Maven project's artifactId (only used for {@link Project})
         * @throws IllegalArgumentException if {@code scope} is used with {@code artifact} or {@code dir},
         *                                  or if the scope value is not recognized
         */
        static Source create(String artifact, String dir, String scope, String projectArtifactId) {
            boolean hasArtifact = artifact != null && !artifact.isBlank();
            boolean hasDir = dir != null && !dir.isBlank();
            boolean hasScope = scope != null && !scope.isBlank();

            if ((hasArtifact ? 1 : 0) + (hasDir ? 1 : 0) + (hasScope ? 1 : 0) > 1) {
                throw new IllegalArgumentException(
                        "Only one of 'artifact', 'dir', or 'scope' can be set, but found multiple");
            } else if (hasArtifact) {
                return new Artifact(artifact);
            } else if (hasDir) {
                return new Dir(dir);
            } else {
                return new Project(Project.Scope.parse(scope), projectArtifactId);
            }
        }

        /**
         * A Maven GAV coordinate resolved from Maven repositories.
         *
         * <p>The coordinate is validated at construction time; the {@code appDir} is
         * pre-computed as {@code artifactId-version} from the GAV parts.
         *
         * @param gav    the raw coordinate string (e.g. {@code groupId:artifactId:version})
         * @param appDir the pre-computed {@code apps/} subdirectory name
         */
        record Artifact(String gav, String appDir) implements Source {

            Artifact(String gav) {
                this(gav, parseAppDir(gav));
            }

            private static String parseAppDir(String gav) {
                var parts = gav.split(":");
                if (parts.length < 2) {
                    throw new IllegalArgumentException(
                            "Malformed artifact coordinate (expected groupId:artifactId[:...]:version): " + gav);
                }
                return parts[1] + "-" + parts[parts.length - 1];
            }
        }

        /** A local directory path (absolute or relative to the project basedir). */
        record Dir(String path) implements Source {
            @Override
            public String appDir() {
                return Path.of(path).getFileName().toString();
            }
        }

        /**
         * The current Maven project's compiled classes and dependencies.
         *
         * @param scope      controls which classes and dependencies are included
         * @param artifactId the Maven project's artifactId, used for the {@code apps/} subdirectory name
         */
        record Project(Scope scope, String artifactId) implements Source {
            public enum Scope {
                COMPILE {
                    @Override
                    public List<Path> classesDirs(Path basePath) {
                        return List.of(basePath.resolve("target/classes"));
                    }

                    @Override
                    public String appDir(String artifactId) {
                        return artifactId;
                    }
                },
                TEST {
                    @Override
                    public List<Path> classesDirs(Path basePath) {
                        var targetDir = basePath.resolve("target");
                        return List.of(targetDir.resolve("classes"), targetDir.resolve("test-classes"));
                    }

                    @Override
                    public String appDir(String artifactId) {
                        return artifactId + "-tests";
                    }
                };

                /**
                 * Returns the class directories to instrument for this scope,
                 * relative to the given project base path.
                 */
                public abstract List<Path> classesDirs(Path basePath);

                /** Returns the {@code apps/} subdirectory name for this scope. */
                public abstract String appDir(String artifactId);

                static Scope parse(String scope) {
                    if (scope == null || scope.isBlank()) {
                        return COMPILE;
                    }
                    return switch (scope) {
                        case "compile" -> COMPILE;
                        case "test" -> TEST;
                        default ->
                            throw new IllegalArgumentException(
                                    "Invalid scope '%s' — valid values are 'compile' and 'test'".formatted(scope));
                    };
                }
            }

            @Override
            public String appDir() {
                return scope.appDir(artifactId);
            }
        }
    }

    /**
     * Describes a service with a typed {@link Source} identifying where its classes come from.
     *
     * <p>The YAML representation uses flat {@code artifact}, {@code dir}, and {@code scope} keys
     * (see example above). A {@link JsonCreator} factory method converts these into a typed
     * {@link Source} at deserialization time, with the Maven project's artifactId injected via
     * {@link JacksonInject}.
     */
    public record ServiceDescriptor(
            Source source, @JsonProperty("class") String className, String ip, List<String> args) {

        public ServiceDescriptor {
            requireNonNull(source, "source");
            requireNonNull(className, "class");
            requireNonNull(ip, "ip");
            args = args == null ? List.of() : args;
        }

        @JsonCreator
        static ServiceDescriptor create(
                @JsonProperty("artifact") String artifact,
                @JsonProperty("dir") String dir,
                @JsonProperty("scope") String scope,
                @JacksonInject(value = PROJECT_ARTIFACT_ID_KEY, useInput = OptBoolean.FALSE) String projectArtifactId,
                @JsonProperty("class") String className,
                @JsonProperty("ip") String ip,
                @JsonProperty("args") List<String> args) {
            return new ServiceDescriptor(Source.create(artifact, dir, scope, projectArtifactId), className, ip, args);
        }

        /** Returns the {@code apps/} subdirectory name for this service's source. */
        public String appDir() {
            return source.appDir();
        }

        public String[] argsArray() {
            return args.toArray(String[]::new);
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
            Source source, @JsonProperty("class") String className) {

        public TraceAuditorDescriptor {
            requireNonNull(source, "source");
            requireNonNull(className, "class");
        }

        @JsonCreator
        static TraceAuditorDescriptor create(
                @JsonProperty("artifact") String artifact,
                @JsonProperty("dir") String dir,
                @JsonProperty("scope") String scope,
                @JacksonInject(value = PROJECT_ARTIFACT_ID_KEY, useInput = OptBoolean.FALSE) String projectArtifactId,
                @JsonProperty("class") String className) {
            return new TraceAuditorDescriptor(Source.create(artifact, dir, scope, projectArtifactId), className);
        }

        /** Returns the {@code apps/} subdirectory name for the trace auditor's source. */
        public String appDir() {
            return source.appDir();
        }
    }
}
