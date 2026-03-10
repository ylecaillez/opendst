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
package com.pingidentity.opendst.example.bank;

import static java.lang.Integer.parseInt;
import static java.lang.System.err;
import static java.lang.System.exit;
import static java.lang.System.out;

import com.pingidentity.opendst.api.Signals;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;

/**
 * A simple TCP account server that holds a single account balance and
 * processes BALANCE, DEBIT and CREDIT commands.
 *
 * <p>Protocol (all values are big-endian ints over a TCP stream):
 * <pre>
 *   Request:  [command: int] [amount: int]   (amount is ignored for BALANCE)
 *   Response: [status: int]  [balance: int]
 * </pre>
 *
 * <p>Commands: 1=BALANCE, 2=DEBIT, 3=CREDIT
 * <p>Status:   0=OK, 1=INSUFFICIENT_FUNDS, 2=INVALID_AMOUNT
 */
public final class AccountServer {

    /** Protocol command constants. */
    public static final int CMD_BALANCE = 1;

    public static final int CMD_DEBIT = 2;
    public static final int CMD_CREDIT = 3;

    /** Response status constants. */
    public static final int STATUS_OK = 0;

    public static final int STATUS_INSUFFICIENT_FUNDS = 1;
    public static final int STATUS_INVALID_AMOUNT = 2;

    private final String name;
    private int balance;

    public AccountServer(String name, int initialBalance) {
        this.name = name;
        this.balance = initialBalance;
    }

    /**
     * Entry point for the deployment descriptor.
     *
     * @param args {@code [port, initialBalance]}
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            err.println("Usage: AccountServer <port> <initialBalance>");
            exit(1);
        }
        var port = parseInt(args[0]);
        var initialBalance = parseInt(args[1]);
        var server = new AccountServer("account", initialBalance);
        Signals.ready();
        server.serve(port);
    }

    /** Returns the current balance (for external inspection, e.g., assertions). */
    public int balance() {
        return balance;
    }

    /**
     * Listens on the given port and processes commands until the connection is closed.
     * Each accepted connection handles one command at a time, sequentially.
     */
    public void serve(int port) throws IOException {
        try (var serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new java.net.InetSocketAddress(port));
            out.printf("[%s] Listening on port %d with balance %d%n", name, port, balance);
            while (true) {
                try (var socket = serverSocket.accept();
                        var in = new DataInputStream(socket.getInputStream());
                        var dataOut = new DataOutputStream(socket.getOutputStream())) {
                    int command = in.readInt();
                    int amount = in.readInt();
                    handleCommand(command, amount, dataOut);
                } catch (IOException e) {
                    out.printf("[%s] Connection error: %s%n", name, e.getMessage());
                }
            }
        }
    }

    private void handleCommand(int command, int amount, DataOutputStream dataOut) throws IOException {
        switch (command) {
            case CMD_BALANCE -> {
                dataOut.writeInt(STATUS_OK);
                dataOut.writeInt(balance);
                out.printf("[%s] BALANCE -> %d%n", name, balance);
            }
            case CMD_DEBIT -> {
                if (amount <= 0) {
                    dataOut.writeInt(STATUS_INVALID_AMOUNT);
                    dataOut.writeInt(balance);
                } else if (amount > balance) {
                    dataOut.writeInt(STATUS_INSUFFICIENT_FUNDS);
                    dataOut.writeInt(balance);
                    out.printf("[%s] DEBIT %d -> INSUFFICIENT_FUNDS (balance=%d)%n", name, amount, balance);
                } else {
                    balance -= amount;
                    dataOut.writeInt(STATUS_OK);
                    dataOut.writeInt(balance);
                    out.printf("[%s] DEBIT %d -> OK (balance=%d)%n", name, amount, balance);
                }
            }
            case CMD_CREDIT -> {
                if (amount <= 0) {
                    dataOut.writeInt(STATUS_INVALID_AMOUNT);
                    dataOut.writeInt(balance);
                } else {
                    balance += amount;
                    dataOut.writeInt(STATUS_OK);
                    dataOut.writeInt(balance);
                    out.printf("[%s] CREDIT %d -> OK (balance=%d)%n", name, amount, balance);
                }
            }
            default -> {
                dataOut.writeInt(STATUS_INVALID_AMOUNT);
                dataOut.writeInt(balance);
            }
        }
        dataOut.flush();
    }
}
