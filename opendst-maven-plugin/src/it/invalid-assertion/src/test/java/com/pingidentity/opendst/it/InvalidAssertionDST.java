package com.pingidentity.opendst.it;

import com.pingidentity.opendst.api.Assert;

public class InvalidAssertionDST {
    public void run() {
        int level = 1;
        // This is INVALID because the message must be a string literal for static discovery.
        // Dynamic strings (concatenation, variables) are rejected during instrumentation.
        Assert.reachable("level-" + level, null);
    }
}
