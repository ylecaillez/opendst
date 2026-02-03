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
import static java.lang.System.err;
import static java.lang.System.exit;

public final class MethodRunner {
    static void main(String[] args) {
        if (args.length < 2) {
            err.println("Usage: java MethodRunner <className> <methodName>");
            exit(1);
        }
        try {
            var clazz = Class.forName(args[0]);
            clazz.getMethod(args[1]).invoke(clazz.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            err.println("Could not instantiate " + args[0]);
            e.printStackTrace();
            getRuntime().halt(1);
        }
    }
}
