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

import static java.lang.Runtime.getRuntime;
import static java.lang.System.arraycopy;
import static java.lang.System.err;
import static java.lang.System.exit;

import com.pingidentity.opendst.Simulator;
import com.pingidentity.opendst.api.LogMonitor;

public final class OpenDstRunner {
    public static void main(String[] args) {
        if (args.length < 2) {
            err.println("Usage: java OpenDstRunner <className> <methodName>");
            exit(1);
        }
        try {
            var clazz = Class.forName(args[0]);
            var instance = clazz.getDeclaredConstructor().newInstance();
            var logMonitor = instance instanceof LogMonitor lm ? lm : (LogMonitor) _ -> {};

            var methodArgs = new String[args.length - 2];
            arraycopy(args, 2, methodArgs, 0, methodArgs.length);

            Simulator.runSimulation(
                    () -> {
                        try {
                            clazz.getMethod(args[1], String[].class).invoke(instance, (Object) methodArgs);
                        } catch (NoSuchMethodException e) {
                            clazz.getMethod(args[1]).invoke(instance);
                        }
                        return null;
                    },
                    logMonitor);
        } catch (ReflectiveOperationException e) {
            err.println("Method invocation failed for " + args[0] + "." + args[1]);
            e.printStackTrace();
            getRuntime().halt(1);
        }
    }
}
