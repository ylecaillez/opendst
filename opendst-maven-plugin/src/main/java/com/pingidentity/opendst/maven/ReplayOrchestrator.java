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

import static java.lang.System.exit;

import com.pingidentity.opendst.maven.ContinuousTestMojo.LogStatement;
import org.apache.maven.plugin.logging.Log;

final class ReplayOrchestrator implements Orchestrator {
    private final Plan plan;
    private final Log logger;
    private String previousLog;

    public ReplayOrchestrator(Log logger, Plan plan) {
        this.logger = logger;
        this.plan = plan;
    }

    @Override
    public Plan nextPlan() {
        return plan;
    }

    @Override
    public void onLogReceived(Plan plan, LogStatement log) {}

    @Override
    public void onPlanTerminated(Plan plan, int code, LogStatement lastLog) {
        if (code == 0) {
            logger.info("The plan completed successfully");
        } else if (code == 1) {
            logger.error("The plan has failed. Last log received: %s".formatted(lastLog));
            exit(1);
        } else {
            logger.error("The plan has failed because of an internal error: %s".formatted(lastLog));
            exit(2);
        }
    }
}
