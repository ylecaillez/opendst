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

import static com.pingidentity.opendst.api.Simulator.startNode;
import static java.lang.Thread.sleep;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

/**
 * A DST scenario used to verify that network operations are correctly
 * intercepted and that fault injection can be triggered.
 */
public class NetworkFaultInjectionDST {
    public void run() throws IOException {
        startNode("server", "10.0.0.1", this::server);
        startNode("client", "10.0.0.2", this::client);
    }

    private Void server() {
        try (ServerSocket serverSocket = new ServerSocket(8080)) {
            Signals.ready();
            for(;;) {
                try (var clientSocket = serverSocket.accept();
                     var in = clientSocket.getInputStream();
                     var out = clientSocket.getOutputStream()) {
                    var buffer = new byte[1024];
                    int read = in.read(buffer);
                    if (read > 0) {
                        Assert.reachable("pong-sent", null);
                        out.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    if (e.getMessage().contains("network-partition")) {
                        throw e;
                    }
                    sleep(1000);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Void client() throws IOException, InterruptedException {
        for (int i = 0; i < 100; i++) {
            try (Socket socket = new Socket("10.0.0.1", 8080);
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {
                Assert.reachable("ping-sent", null);
                out.write("ping".getBytes());
                byte[] buffer = new byte[1024];
                in.read(buffer);
            } catch (IOException e) {
                if (e.getMessage().contains("network-partition")) {
                    throw e;
                }
                sleep(1000);
            }
        }
        return null;
    }
}
