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

import com.pingidentity.opendst.common.DeploymentDescriptor.ServiceDescriptor;
import com.pingidentity.opendst.common.DeploymentDescriptor.Source;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Serializes {@link ServiceDescriptor} to flat YAML/JSON fields, writing the {@link Source}
 * as its constituent {@code artifact}, {@code dir}, or {@code scope} key rather than a
 * nested {@code source} object.
 *
 * <p>This is needed because {@code ServiceDescriptor} stores a typed {@link Source} field
 * internally, but the YAML representation uses flat sibling keys.
 */
final class ServiceDescriptorSerializer extends StdSerializer<ServiceDescriptor> {

    ServiceDescriptorSerializer() {
        super(ServiceDescriptor.class);
    }

    @Override
    public void serialize(ServiceDescriptor svc, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeStartObject();
        writeSource(gen, svc.source());
        if (svc.className() != null) {
            gen.writeStringProperty("class", svc.className());
        }
        if (svc.ip() != null) {
            gen.writeStringProperty("ip", svc.ip());
        }
        if (svc.args() != null && !svc.args().isEmpty()) {
            gen.writeArrayPropertyStart("args");
            for (var arg : svc.args()) {
                gen.writeString(arg);
            }
            gen.writeEndArray();
        }
        gen.writeEndObject();
    }

    static void writeSource(JsonGenerator gen, Source source) {
        switch (source) {
            case Source.Artifact(var gav) -> gen.writeStringProperty("artifact", gav);
            case Source.Dir(var path) -> gen.writeStringProperty("dir", path);
            case Source.Project(var scope, var artifactId) -> {
                if (scope != Source.Project.Scope.COMPILE) {
                    gen.writeStringProperty("scope", scope.name().toLowerCase());
                }
            }
        }
    }
}
