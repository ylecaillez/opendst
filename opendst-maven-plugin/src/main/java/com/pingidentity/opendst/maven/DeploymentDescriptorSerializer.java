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
package com.pingidentity.opendst.maven;

import static tools.jackson.databind.SerializationFeature.INDENT_OUTPUT;

import com.pingidentity.opendst.common.DeploymentDescriptor;
import com.pingidentity.opendst.common.DeploymentDescriptor.ServiceDescriptor;
import com.pingidentity.opendst.common.DeploymentDescriptor.Source;
import com.pingidentity.opendst.common.DeploymentDescriptor.TraceAuditorDescriptor;
import java.io.IOException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Serializes a {@link DeploymentDescriptor} to YAML, writing the typed {@link Source}
 * as flat sibling keys ({@code artifact}, {@code dir}, or {@code scope}) rather than
 * a nested {@code source} object.
 *
 * <p>This is needed because the in-memory model uses a sealed {@link Source} interface,
 * but the YAML representation uses flat keys at the same level as {@code class} and {@code ip}.
 */
final class DeploymentDescriptorSerializer {

    private DeploymentDescriptorSerializer() {}

    /**
     * Serializes the enriched deployment descriptor to YAML bytes for baking into the output JAR.
     */
    static byte[] serialize(DeploymentDescriptor descriptor) throws IOException {
        var serializerModule = new SimpleModule()
                .addSerializer(ServiceDescriptor.class, new ServiceDescriptorStdSerializer())
                .addSerializer(TraceAuditorDescriptor.class, new TraceAuditorDescriptorStdSerializer());
        var yamlMapper = YAMLMapper.builder()
                .addModule(serializerModule)
                .enable(INDENT_OUTPUT)
                .build();
        return yamlMapper.writeValueAsBytes(descriptor);
    }

    @SuppressWarnings("PMD.UnusedLocalVariable") // record pattern bindings required by switch
    private static void writeSource(JsonGenerator gen, Source source) {
        switch (source) {
            case Source.Artifact(var gav, var appDirName) -> gen.writeStringProperty("artifact", gav);
            case Source.Dir(var path) -> gen.writeStringProperty("dir", path);
            case Source.Project(var scope, var artifactId) -> {
                if (scope != Source.Project.Scope.COMPILE) {
                    gen.writeStringProperty("scope", scope.name().toLowerCase());
                }
            }
        }
    }

    private static final class ServiceDescriptorStdSerializer extends StdSerializer<ServiceDescriptor> {

        ServiceDescriptorStdSerializer() {
            super(ServiceDescriptor.class);
        }

        @Override
        public void serialize(ServiceDescriptor svc, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeStartObject();
            writeSource(gen, svc.source());
            gen.writeStringProperty("class", svc.className());
            gen.writeStringProperty("ip", svc.ip());
            if (!svc.args().isEmpty()) {
                gen.writeName("args");
                gen.writeArray(svc.argsArray(), 0, svc.argsArray().length);
            }
            gen.writeEndObject();
        }
    }

    private static final class TraceAuditorDescriptorStdSerializer extends StdSerializer<TraceAuditorDescriptor> {

        TraceAuditorDescriptorStdSerializer() {
            super(TraceAuditorDescriptor.class);
        }

        @Override
        public void serialize(TraceAuditorDescriptor ta, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeStartObject();
            writeSource(gen, ta.source());
            gen.writeStringProperty("class", ta.className());
            gen.writeEndObject();
        }
    }
}
