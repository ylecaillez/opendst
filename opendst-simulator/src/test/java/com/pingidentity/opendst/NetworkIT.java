/*
 * Copyright 2025-2026 Ping Identity Corporation
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
package com.pingidentity.opendst;

import static com.pingidentity.opendst.Simulator.runSimulation;
import static java.lang.Thread.sleep;
import static java.net.InetAddress.getByAddress;
import static java.net.InetAddress.getByName;
import static java.net.InetAddress.getLocalHost;
import static java.net.InetAddress.getLoopbackAddress;
import static java.net.InetAddress.ofLiteral;
import static java.net.StandardSocketOptions.SO_RCVBUF;
import static java.net.StandardSocketOptions.SO_REUSEADDR;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

public class NetworkIT {
    @Test
    public void inetAddressCanResolve() throws ExecutionException, UnknownHostException {
        var realHostName = getLocalHost().getHostName();
        runSimulation(() -> {
            // Simulation only have "127.0.0.1"

            var localHostAddress = getLocalHost();
            var hostName = localHostAddress.getHostName();
            // getHostName() returns an IP address if name cannot be resolved.
            assertThatException().isThrownBy(() -> ofLiteral(hostName)).isInstanceOf(IllegalArgumentException.class);
            assertThat(hostName).isEqualTo("localhost");

            var address = localHostAddress.getAddress();
            assertThat(getByAddress(address).getHostName()).isEqualTo(hostName);
            assertThat(getByAddress(address).getCanonicalHostName()).isEqualTo(hostName);

            var hostAddress = localHostAddress.getHostAddress();
            assertThat(ofLiteral(hostAddress).getHostName()).isEqualTo(hostName);
            assertThat(ofLiteral(hostAddress).getCanonicalHostName()).isEqualTo(hostName);

            assertThat(getLoopbackAddress().getHostName()).isEqualTo("localhost");
            assertThat(getLoopbackAddress().getCanonicalHostName()).isEqualTo("localhost");
            assertThat(getByName("localhost")).isEqualTo(getLoopbackAddress());
            assertThat(getByAddress(getLoopbackAddress().getAddress()).getHostName())
                    .isEqualTo("localhost");
            assertThat(getByAddress(getLoopbackAddress().getAddress()).getCanonicalHostName())
                    .isEqualTo("localhost");

            assertThat(getByName(hostName)).isEqualTo(localHostAddress);
            return null;
        });
    }

    @Test
    public void echoClientServer() throws ExecutionException {
        // TODO: Bring back JQF
        int dataSize = 1024;
        int clientSocketBufferSize = 256;
        int serverSocketBufferSize = 256;
        int transferBufferSize = 256;
        long seed = 42;
        // This is an input generation optimization so that JQF does not exhaust itself at generating random byte data.
        // Indeed, generating different data will less likely discovering new code path than changing buffer's sizes.
        // As such, only the buffer size will be driven by the code-coverage driven input generator (i.e. JQF). The
        // buffer content itself will be driven by a standard, seeded, random generator.
        var data = new byte[dataSize];
        new Random(seed).nextBytes(data);

        runSimulation(() -> {
            var echoServer = new FutureTask<>(new EchoServer(1234, transferBufferSize, serverSocketBufferSize));
            var echoClient = new FutureTask<>(new EchoClient(getLoopbackAddress(), 1234, clientSocketBufferSize, data));

            new Thread(echoServer, "EchoServer").start();
            new Thread(echoClient, "EchoClient").start();

            assertThat(echoClient.get(30, SECONDS)).containsExactly(data);
            return null;
        });
    }

    record EchoServer(int port, int transferBufferSize, int socketBufferSize) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            var transferBuffer = new byte[transferBufferSize];
            try (var serverSocket = new ServerSocket()) {
                serverSocket.setOption(SO_REUSEADDR, true);
                serverSocket.bind(new InetSocketAddress(port));
                try (var socket = serverSocket.accept()) {
                    socket.setOption(SO_RCVBUF, socketBufferSize);
                    // Write back all the data received by client until EOF.
                    // First, fill the transferBuffer by reading data from client.
                    // Then send back the transferBuffer content.
                    var is = socket.getInputStream();
                    var os = socket.getOutputStream();
                    for (; ; ) {
                        int read, dataLen;
                        for (dataLen = 0; dataLen < transferBuffer.length; dataLen += read) {
                            if ((read = is.read(transferBuffer, dataLen, transferBuffer.length - dataLen)) == -1) {
                                // EOF: all the data have been received
                                os.write(transferBuffer, 0, dataLen);
                                return null;
                            }
                        }
                        os.write(transferBuffer, 0, dataLen);
                    }
                }
            }
        }
    }

    record EchoClient(InetAddress address, int port, int socketBufferSize, byte[] data) implements Callable<byte[]> {
        public byte[] call() throws Exception {
            for (; ; ) {
                try (var socket = new Socket(address, port)) {
                    socket.setOption(SO_RCVBUF, socketBufferSize);
                    new Thread(
                                    new FutureTask<>(() -> {
                                        // Write data to the echo server
                                        socket.getOutputStream().write(data, 0, data.length);
                                        socket.shutdownOutput();
                                        return null;
                                    }),
                                    "EchoClientSender")
                            .start();

                    var receiver = new FutureTask<>(() -> {
                        // Read data from the echo server
                        var received = new byte[data.length];
                        var is = socket.getInputStream();
                        for (int bytesRead = 0, totalRead = 0;
                                bytesRead != -1 && totalRead < received.length;
                                totalRead += bytesRead) {
                            bytesRead = is.read(received, totalRead, received.length - totalRead);
                        }
                        return received;
                    });
                    new Thread(receiver, "EchoClientReceiver").start();
                    return receiver.get(3, SECONDS);
                } catch (IOException e) {
                    // Maybe server is not started yet, try again
                    sleep(1000);
                }
            }
        }
    }
}
