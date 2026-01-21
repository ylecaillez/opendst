package com.pingidentity.opendst.it;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

public class LivenessViolationDST {
    public void run() {
        Signals.ready();
        // This 'sometimes' is never true, so the session should fail
        Assert.sometimes(false, "impossible-liveness", null);
    }
}
