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
package com.pingidentity.opendst.testapp;

import static java.lang.System.err;
import static java.lang.System.exit;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Random;

public final class Client {
    private static final int MAX_VALUE = 20;

    public static void main(String[] args) {
        if (args.length < 2) {
            err.println("Usage: Client <host> <port>");
            exit(1);
        }
        try {
            new Client().run(args[0], Integer.parseInt(args[1]));
        } catch (IOException e) {
            err.println("Client failed: " + e.getMessage());
        }
    }

    public void run(String host, int port) throws IOException {
        var random = new Random();
        try (var socket = new Socket(host, port);
             var out = new DataOutputStream(socket.getOutputStream())) {
            for (;;) {
                int nextGuess = random.nextInt(MAX_VALUE);
                out.writeInt(nextGuess);
                out.flush();
            }
        }
    }
}
