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
package com.pingidentity.opendst.it.networkfault;

import com.pingidentity.opendst.api.TraceAuditor;

/**
 * Basic trace auditor for the network fault IT.
 */
public final class NetworkFaultTraceAuditor implements TraceAuditor {
    @Override
    public void process(Log log) throws Throwable {
        // No-op for now, basic connectivity check is done by the app assertions.
    }
}
