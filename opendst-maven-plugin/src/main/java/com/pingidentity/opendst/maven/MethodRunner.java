package com.pingidentity.opendst.maven;

import static java.lang.System.err;
import static java.lang.System.exit;

public final class MethodRunner {
    static void main(String[] args) throws ReflectiveOperationException {
        if (args.length < 2) {
            err.println("Usage: java MethodRunner <className> <methodName>");
            exit(1);
        }
        var clazz = Class.forName(args[0]);
        clazz.getMethod(args[1]).invoke(clazz.getDeclaredConstructor().newInstance());
    }
}
