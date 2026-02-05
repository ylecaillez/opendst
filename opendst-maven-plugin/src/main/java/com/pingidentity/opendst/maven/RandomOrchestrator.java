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
import static java.util.concurrent.ThreadLocalRandom.current;

import com.pingidentity.opendst.maven.ContinuousTestMojo.LogStatement;
import com.pingidentity.opendst.maven.Orchestrator.Plan.Segment;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.maven.plugin.logging.Log;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class RandomOrchestrator implements Orchestrator {
    private final Log logger;
    private final File failureDir;
    private final long duration;
    private final double replayProbability;
    private final Map<String, Plan> pastPlans = new ConcurrentHashMap<>();
    private final Map<String, LogStatement> lastLogs = new ConcurrentHashMap<>();
    private long planCount = 0;

    RandomOrchestrator(Log logger, File failureDir, long duration, double replayProbability) {
        this.logger = logger;
        this.failureDir = failureDir;
        this.duration = duration;
        this.replayProbability = replayProbability;
    }

    @Override
    public synchronized Plan nextPlan() {
        if (!pastPlans.isEmpty() && current().nextDouble() < replayProbability) {
            var keys = new ArrayList<>(pastPlans.keySet());
            var rid = keys.get(current().nextInt(keys.size()));
            var originalPlan = pastPlans.get(rid);
            var replayRid = "replay-" + rid + "-" + (++planCount);
            var replayPlan = new Plan(replayRid, originalPlan.segments());
            logger.info("Replaying plan %s (original rid: %s) to validate determinism".formatted(replayRid, rid));
            return replayPlan;
        }
        return new Plan(
                Long.toString(++planCount), List.of(new Segment(current().nextLong(), duration)));
    }

    @Override
    public void onLogReceived(Plan plan, LogStatement log) {
        // Ignored
    }

    @Override
    public synchronized void onPlanTerminated(Plan plan, int code, LogStatement lastLog) {
        if (code != 0) {
            logger.error("Simulation failed with code %d. Last log received: %s".formatted(code, lastLog));
            savePlanAndExit(plan);
        } else if (plan.rid().startsWith("replay-")) {
            var originalRid = plan.rid().split("-")[1];
            var originalLastLog = lastLogs.get(originalRid);
            if (originalLastLog == null) {
                logger.info(("Determinism cannot be verified for replayed plan %s as there was no log produced")
                        .formatted(plan.rid()));
            } else if (originalLastLog.equals(lastLog)) {
                logger.info("Deterministic execution verified for replayed plan %s".formatted(plan.rid()));
            } else {
                logger.error("A non-deterministic execution has been detected for plan %s".formatted(plan.rid()));
                logger.error("Expected last log: %s".formatted(originalLastLog));
                logger.error("Actual last log:   %s".formatted(lastLog));
                savePlanAndExit(plan);
            }
        } else {
            lastLogs.put(plan.rid(), lastLog);
            pastPlans.put(plan.rid(), plan);
        }
    }

    private void savePlanAndExit(Plan plan) {
        failureDir.mkdirs();
        var mapper = new ObjectMapper();
        for (int i = 0; i < 1000; i++) {
            var failureFile = new File(failureDir, "failure-%d.json".formatted(i));
            try {
                if (failureFile.createNewFile()) {
                    mapper.writerFor(Plan.class).writeValue(failureFile, plan);
                    logger.error("Plan saved to '%s'".formatted(failureFile));
                    exit(1);
                }
            } catch (IOException | JacksonException e) {
                logger.error("Unable to save the failing plan", e);
                exit(1);
            }
        }
        logger.error("Unable to save the plan because the failure directory '%s' already contains 1000 failures"
                .formatted(failureDir.getAbsolutePath()));
        exit(1);
    }
}
