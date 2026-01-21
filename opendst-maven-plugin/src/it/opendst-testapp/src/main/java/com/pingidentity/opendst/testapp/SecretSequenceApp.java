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

import static java.lang.Integer.parseInt;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;
import static java.lang.Thread.sleep;
import static java.util.Objects.requireNonNull;

import com.pingidentity.opendst.api.Assert;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.Random;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * A sample application where a client attempts to guess a secret sequence on a server over SSL.
 */
public final class SecretSequenceApp {

    private static final int[] SECRET_SEQUENCE = {1, 2, 3};
    private static final int MAX_GUESS = 20;

    /**
     * Simulated server that unlocks levels based on received secret codes.
     */
    public static final class Server {
        private final DeterminismStressor stressor = new DeterminismStressor();

        public static void main(String[] args) throws Exception {
            requireNonNull(args);
            if (args.length < 1) {
                err.println("Usage: Server <port>");
                exit(1);
            }
            var port = parseInt(args[0]);
            var sslContext = createSSLContext();
            var ssf = sslContext.getServerSocketFactory();
            try (var serverSocket = ssf.createServerSocket(port)) {
                out.println("SSL Server started on port " + serverSocket.getLocalPort());
                new Server().run(serverSocket);
            } catch (IOException e) {
                err.println("Server failed: " + e.getMessage());
            } finally {
                out.println("Server stopped");
            }
        }

        public void run(ServerSocket serverSocket) throws IOException {
            int currentLevel = 0;
            try (var socket = serverSocket.accept();
                 var in = new DataInputStream(socket.getInputStream())) {
                for (;;) {
                    int code = in.readInt();
                    if (code == SECRET_SEQUENCE[currentLevel]) {
                        currentLevel++;
                        out.printf("Level %d unlocked (vs %d)%n", currentLevel, SECRET_SEQUENCE.length);
                        switch (currentLevel) {
                            case 1 -> Assert.reachable("level-1", null);
                            case 2 -> Assert.reachable("level-2", null);
                            case 3 -> {
                                out.println("Bug reached!");
                                return;
                            }
                        }
                    } else {
                        out.printf("Bug missed, try again ! (received %d, expecting %d)%n", code,
                                   SECRET_SEQUENCE[currentLevel]);
                        return;
                    }
                    stressor.stress(code);
                    socket.getOutputStream().write(0);
                    socket.getOutputStream().flush();
                }
            } catch (IOException e) {
                if (!e.getMessage().contains("Socket closed")) {
                    err.println("Server session failed: " + e.getMessage());
                }
                throw e;
            }
        }
    }

    /**
     * Simulated client that randomly guesses codes to advance server levels.
     */
    public static final class Client {
        public static void main(String[] args) throws Exception {
            requireNonNull(args);
            if (args.length < 2) {
                err.println("Usage: Client <host> <port>");
                exit(1);
            }
            new Client().run(args[0], Integer.parseInt(args[1]));
        }

        public void run(String host, int port) throws Exception {
            var random = new Random();
            var sslContext = createSSLContext();
            var sf = sslContext.getSocketFactory();
            for (int i = 0; i < 10; i++) {
                try (var socket = sf.createSocket(host, port);
                     var dataOut = new DataOutputStream(socket.getOutputStream());
                     var in = socket.getInputStream()) {
                    for (int j = 0; j < SECRET_SEQUENCE.length; j++) {
                        dataOut.writeInt(random.nextInt(MAX_GUESS));
                        dataOut.flush();
                        if (in.read() == -1) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    // Wait for server or retry
                    sleep(1000);
                }
            }
        }
    }

    private static SSLContext createSSLContext() throws Exception {
        var charPassword = "password".toCharArray();
        var ks = KeyStore.getInstance("JKS");
        try (var is = SecretSequenceApp.class.getResourceAsStream("/keystore.jks")) {
            ks.load(is, charPassword);
        }

        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, charPassword);

        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }

    private SecretSequenceApp() {
    }
}
