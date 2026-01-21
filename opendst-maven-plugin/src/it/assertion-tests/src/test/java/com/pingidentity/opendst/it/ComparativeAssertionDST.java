package com.pingidentity.opendst.it;

import java.util.concurrent.ThreadLocalRandom;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

/**
 * Tests that comparative assertions emit guidance data (left/right values)
 * that the orchestrator can use for distance-guided exploration.
 *
 * <p>The random value will vary across simulation runs. The orchestrator should
 * track the minimum distance-to-violation and prefer plans that narrow the gap.
 * The assertion always passes (value is always >= 0 and we assert > -1),
 * so this DST should succeed.
 */
public class ComparativeAssertionDST {
    public void run() {
        Signals.ready();
        int value = ThreadLocalRandom.current().nextInt(100);
        Assert.alwaysGreaterThan(value, -1, "comparative-safety", null);
    }
}
