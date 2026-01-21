package com.pingidentity.opendst.it;

import com.pingidentity.opendst.api.Assert;

public class ValidAssertionDST {
    public void run() {
        Assert.reachable("level-1", null);
        Assert.always(true, "invariant", null);
    }
}
