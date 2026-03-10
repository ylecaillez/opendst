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
package com.pingidentity.opendst;

import java.util.Objects;

/**
 * Incrementally builds a hash of the execution trace to detect non-determinism.
 */
final class StateHasher {
    private int hash = 0;

    /**
     * Updates the hash with provided objects.
     * Objects must have deterministic hashCode() (e.g. Strings, Numbers, Records).
     */
    synchronized void update(Object... objects) {
        for (var o : objects) {
            hash = 31 * hash + Objects.hashCode(o);
        }
    }

    int getHash() {
        return hash;
    }
}
