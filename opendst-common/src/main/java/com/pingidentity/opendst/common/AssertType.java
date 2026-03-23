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

import com.fasterxml.jackson.annotation.JsonCreator;

/** Classification of OpenDST assertions. */
public enum AssertType {
    ALWAYS("always", true),
    ALWAYS_OR_UNREACHABLE("alwaysOrUnreachable", false),
    SOMETIMES("sometimes", true);

    private final String value;
    private final boolean mustHit;

    AssertType(String value, boolean mustHit) {
        this.value = value;
        this.mustHit = mustHit;
    }

    public boolean mustHit() {
        return mustHit;
    }

    @JsonCreator
    public static AssertType fromString(String value) {
        for (var type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown assert type: " + value);
    }

    @Override
    @com.fasterxml.jackson.annotation.JsonValue
    public String toString() {
        return value;
    }
}
