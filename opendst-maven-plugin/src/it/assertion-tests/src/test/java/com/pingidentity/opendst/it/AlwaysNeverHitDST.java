package com.pingidentity.opendst.it;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

public class AlwaysNeverHitDST {
    public void run() {
        Signals.ready();
        // This method is called, but doesn't contain the assertion.
        // The assertion is in 'unreachableMethod' below.
    }

    private void unreachableMethod() {
        // This will be discovered by offline instrumentation, 
        // but never hit during the simulation.
        Assert.always(true, "dead-code-assertion", null);
    }
}
