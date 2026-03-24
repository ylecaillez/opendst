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

import com.pingidentity.opendst.common.DeploymentDescriptor.TraceAuditorDescriptor;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Serializes {@link TraceAuditorDescriptor} to flat YAML/JSON fields, writing the source
 * as its constituent {@code artifact}, {@code dir}, or {@code scope} key rather than a
 * nested {@code source} object.
 */
final class TraceAuditorDescriptorSerializer extends StdSerializer<TraceAuditorDescriptor> {

    TraceAuditorDescriptorSerializer() {
        super(TraceAuditorDescriptor.class);
    }

    @Override
    public void serialize(TraceAuditorDescriptor ta, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeStartObject();
        ServiceDescriptorSerializer.writeSource(gen, ta.source());
        if (ta.className() != null) {
            gen.writeStringProperty("class", ta.className());
        }
        gen.writeEndObject();
    }
}
