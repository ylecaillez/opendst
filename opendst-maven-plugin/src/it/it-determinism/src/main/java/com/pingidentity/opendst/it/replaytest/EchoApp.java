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
package com.pingidentity.opendst.it.replaytest;

import static java.lang.Integer.parseInt;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;

import com.pingidentity.opendst.api.Assert;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * A simple echo application for testing the {@code --plan} replay feature.
 *
 * <p>The server listens for integer values and echoes them back incremented by one.
 * The client sends a sequence of values and verifies the responses.
 */
public final class EchoApp {

    /** Echo server that reads integers and writes back value + 1. */
    public static final class Server {
        public static void main(String[] args) throws Exception {
            if (args.length < 1) {
                err.println("Usage: Server <port>");
                exit(1);
            }
            var port = parseInt(args[0]);
            try (var serverSocket = new ServerSocket(port)) {
                out.println("Echo server started on port " + serverSocket.getLocalPort());
                try (var socket = serverSocket.accept();
                     var in = new DataInputStream(socket.getInputStream());
                     var dataOut = new DataOutputStream(socket.getOutputStream())) {
                    for (;;) {
                        int value = in.readInt();
                        if (value == -1) {
                            out.println("Server received shutdown signal");
                            break;
                        }
                        Assert.reachable("server-echo", null);
                        dataOut.writeInt(value + 1);
                        dataOut.flush();
                    }
                }
            } catch (IOException e) {
                err.println("Server error: " + e.getMessage());
            } finally {
                out.println("Echo server stopped");
            }
        }
    }

    /** Echo client that sends integers and reads back responses. */
    public static final class Client {
        public static void main(String[] args) throws Exception {
            if (args.length < 2) {
                err.println("Usage: Client <host> <port>");
                exit(1);
            }
            var host = args[0];
            var port = parseInt(args[1]);
            try (var socket = new Socket(host, port);
                 var dataOut = new DataOutputStream(socket.getOutputStream());
                 var in = new DataInputStream(socket.getInputStream())) {
                for (int i = 0; i < 5; i++) {
                    dataOut.writeInt(i);
                    dataOut.flush();
                    int response = in.readInt();
                    Assert.reachable("client-received", null);
                    out.println("Sent " + i + ", received " + response);
                }
                // Send shutdown
                dataOut.writeInt(-1);
                dataOut.flush();
                Assert.reachable("client-done", null);
            } catch (IOException e) {
                err.println("Client error: " + e.getMessage());
            }
        }
    }

    private EchoApp() {
    }
}
