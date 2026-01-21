package com.pingidentity.opendst.it;

public class MultiMethodDST {
    public void firstMethod() {
        throw new AssertionError("firstMethod run");
    }
    public void secondMethod() {
        throw new AssertionError("secondMethod run");
    }
    public void otherTask() {
        throw new AssertionError("otherTask run");
    }
}
