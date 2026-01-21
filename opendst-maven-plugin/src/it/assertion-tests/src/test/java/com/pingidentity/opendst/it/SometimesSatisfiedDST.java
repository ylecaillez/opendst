package com.pingidentity.opendst.it;

import java.util.concurrent.ThreadLocalRandom;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

public class SometimesSatisfiedDST {
    public void run() {
        Signals.ready();
        // In a session with multiple random seeds, some runs will have even salt
        boolean isEven = ThreadLocalRandom.current().nextBoolean();
        Assert.sometimes(isEven, "probabilistic-liveness", null);
    }
}
