/*
 * Copyright 2025 Ping Identity Corporation
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
import static com.pingidentity.opendst.Simulator.startNode;
import static com.pingidentity.opendst.Simulator.stopNode;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.lang.Thread.ofPlatform;
import static java.lang.Thread.sleep;
import static java.net.InetAddress.getByAddress;
import static java.net.InetAddress.getByName;
import static java.net.InetAddress.getLocalHost;
import static java.net.InetAddress.getLoopbackAddress;
import static java.net.InetAddress.ofLiteral;
import static java.net.StandardSocketOptions.SO_RCVBUF;
import static java.net.StandardSocketOptions.SO_REUSEADDR;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

public class NodeIT {
    @Test
    public void inetAddressCanResolve() throws Exception {
        runSimulation(() -> {
            startNode("node-hostname", "10.0.0.1", () -> {
                var localHostAddress = getLocalHost();
                assertThat(localHostAddress).isEqualTo(InetAddress.ofLiteral("10.0.0.1"));
                var hostName = localHostAddress.getHostName();
                // getHostName() returns an IP address if name cannot be resolved.
                assertThatException().isThrownBy(() -> ofLiteral(hostName))
                                     .isInstanceOf(IllegalArgumentException.class);
                assertThat(hostName).isEqualTo("node-hostname");

                var address = localHostAddress.getAddress();
                assertThat(getByAddress(address).getHostName()).isEqualTo(hostName);
                assertThat(getByAddress(address).getCanonicalHostName()).isEqualTo(hostName);

                var hostAddress = localHostAddress.getHostAddress();
                assertThat(ofLiteral(hostAddress).getHostName()).isEqualTo(hostName);
                assertThat(ofLiteral(hostAddress).getCanonicalHostName()).isEqualTo(hostName);

                assertThat(getLoopbackAddress().getHostName()).isEqualTo("localhost");
                assertThat(getLoopbackAddress().getCanonicalHostName()).isEqualTo("localhost");
                assertThat(getByName("localhost")).isEqualTo(getLoopbackAddress());
                assertThat(getByAddress(getLoopbackAddress().getAddress()).getHostName()).isEqualTo("localhost");
                assertThat(getByAddress(getLoopbackAddress().getAddress()).getCanonicalHostName()).isEqualTo(
                        "localhost");

                assertThat(getByName(hostName)).isEqualTo(localHostAddress);
                return null;
            });
            return null;
        });
    }

    @Test
    public void canStopNode() throws Exception {
        runSimulation(() -> {
            var shutdownHookExecuted = new AtomicBoolean();
            var threadInterrupted = new AtomicBoolean();
            startNode("node-hostname", "10.0.0.1", () -> {
                var neverStarted = new Thread(); // This thread should not prevent shutdown given it is not started
                getRuntime().addShutdownHook(new Thread(() -> shutdownHookExecuted.set(true)));
                ofPlatform().name("interruptible-thread").start(() -> threadInterrupted.set(interruptibleSleep()));
                return null;
            });
            sleep(ofSeconds(5));
            stopNode("node-hostname");
            sleep(ofSeconds(60));

            assertThat(threadInterrupted.get()).describedAs("Remaining alive thread should be interrupted").isTrue();
            assertThat(shutdownHookExecuted.get()).describedAs("Stopping node should trigger shutdown hooks").isTrue();
            return null;
        });
    }

    private static boolean interruptibleSleep() {
        for (;;) {
            try {
                sleep(ofSeconds(1));
            } catch (InterruptedException e) {
                // As a last attempt, the simulator should try to interrupt threads which are still running
                return true;
            }
        }
    }

    @Test
    public void nodeCanExit() throws Exception {
        runSimulation(() -> {
            var shutdownHookExecuted = new AtomicBoolean();
            var threadInterrupted = new AtomicBoolean();
            var codeAfterExitExecuted = new AtomicBoolean();
            startNode("node-hostname", "10.0.0.1", () -> {
                var neverStarted = new Thread(); // This thread should not prevent shutdown given it is not started
                getRuntime().addShutdownHook(new Thread(() -> shutdownHookExecuted.set(true)));
                ofPlatform().name("interruptible-thread").start(() -> threadInterrupted.set(interruptibleSleep()));
                sleep(ofSeconds(5));
                getRuntime().exit(0);
                codeAfterExitExecuted.set(true);
                return null;
            });
            sleep(ofSeconds(60));

            assertThat(threadInterrupted.get()).describedAs("Remaining alive thread should be interrupted").isTrue();
            assertThat(shutdownHookExecuted.get()).describedAs("Stopping node should trigger shutdown hooks").isTrue();
            assertThat(codeAfterExitExecuted.get()).describedAs("Code after Runtime#exit() must not be executed")
                                                   .isFalse();
            return null;
        });
    }

    @Test
    public void echoClientServer() throws Exception {
        // TODO: Bring back JQF
        int dataSize = 1024;
        var clientSocketBufferSize = List.of(1, 2, 4, 8, 16, 32, 64, 128, 256, 512);
        int serverBufferSize = 256;
        int transferBufferSize = 256;
        long seed = 42;

        var data = new byte[dataSize];
        new Random(seed).nextBytes(data);

        int[] clientBufferSize = clientSocketBufferSize.stream().mapToInt(Integer::intValue).toArray();
        runSimulation(() -> {
            // start server
            startNode("echo-server", "10.0.0.1", new EchoServer(1234, transferBufferSize, serverBufferSize));

            // start clients
            for (int i = 0; i < clientBufferSize.length; i++) {
                var host = format("echo-client-%d", i + 1);
                var ip = format("10.1.0.%d", i + 1);
                startNode(host, ip, new EchoClient("echo-server", 1234, clientBufferSize[i], data));
            }

            sleep(ofSeconds(30));

            stopNode("echo-server");
            return null;
        });
    }

    record EchoServer(int port, int transferBufferSize, int socketBufferSize) implements Callable<Void> {
        @Override
        public Void call() throws Exception {
            try (var serverSocket = new ServerSocket()) {
                serverSocket.setOption(SO_REUSEADDR, true);
                serverSocket.bind(new InetSocketAddress(port));
                getRuntime().addShutdownHook(new Thread(() -> assertThatNoException().isThrownBy(serverSocket::close)));
                for (;;) {
                    var socket = serverSocket.accept();
                    new Thread(() -> assertThatNoException().isThrownBy(() ->
                        new Connection(socket, transferBufferSize, socketBufferSize).call())).start();
                }
            } catch (IOException e) {
                assertThat(e).hasMessageContaining("Socket closed");
                return null;
            }
        }

        record Connection(Socket socket, int transferBufferSize, int socketBufferSize) implements Callable<Void> {
            @Override
            public Void call() throws Exception {
                var transferBuffer = new byte[transferBufferSize];
                try (socket) {
                    // Exercise fragmentation by using different socket buffer.
                    // Note that there is only one buffer between two sockets. SO_SNDBUF is not supported.
                    socket.setOption(SO_RCVBUF, socketBufferSize);
                    // Write back all the data received by client until EOF.
                    // First, fill the transferBuffer by reading data from client.
                    // Then send back the transferBuffer content.
                    var is = socket.getInputStream();
                    var os = socket.getOutputStream();
                    for (;;) {
                        int read, dataLen;
                        for (dataLen = 0; dataLen < transferBuffer.length; dataLen += read) {
                            read = is.read(transferBuffer, dataLen, transferBuffer.length - dataLen);
                            if (read == -1) {
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

    record EchoClient(String host, int port, int socketBufferSize, byte[] data) implements Callable<Void> {
        public Void call() throws Exception {
            for (int i = 0; i < 10; i++) {
                try (var socket = new Socket(host, port)) {
                    // Exercise fragmentation by using different socket buffer.
                    // Note that there is only one buffer between two sockets. SO_SNDBUF is not supported.
                    socket.setOption(SO_RCVBUF, socketBufferSize);
                    new Thread(new FutureTask<>(() -> {
                        // Write data to the echo server
                        socket.getOutputStream().write(data, 0, data.length);
                        socket.shutdownOutput();
                        return null;
                    }), "EchoClientSender").start();

                    var receiver = new FutureTask<>(() -> {
                        // Read data from the echo server
                        var received = new byte[data.length];
                        var is = socket.getInputStream();
                        for (int bytesRead = 0, totalRead = 0; bytesRead != -1 && totalRead < received.length;
                             totalRead += bytesRead) {
                            bytesRead = is.read(received, totalRead, received.length - totalRead);
                        }
                        return received;
                    });
                    new Thread(receiver, "EchoClientReceiver").start();
                    assertThat(receiver.get(30, SECONDS)).isEqualTo(data);
                    return null;
                } catch (IOException e) {
                    // Server might not be ready yet
                    sleep(1000);
                }
            }
            fail(currentThread().getName() + "- unable to connect to " + host + ":" + port);
            return null;
        }
    }
}
