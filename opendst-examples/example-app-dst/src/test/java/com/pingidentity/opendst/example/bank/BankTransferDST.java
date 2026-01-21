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

import static com.pingidentity.opendst.api.Simulator.startNode;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic simulation test for the bank transfer service.
 *
 * <p>Sets up two account servers ("alice" and "bob") each with an initial
 * balance of 1000, plus a coordinator node that performs random transfers
 * between them. After each transfer, a checker node queries both balances
 * and asserts the system invariant: the total money must be conserved.
 *
 * <p>Under normal conditions this always passes. When OpenDST injects
 * network faults (latency, partitions, packet loss), the naive transfer
 * service breaks:
 * <ul>
 *   <li>Money is destroyed when a credit fails after a successful debit</li>
 *   <li>Money is created when a timed-out credit is retried but the
 *       original actually succeeded</li>
 * </ul>
 */
public class BankTransferDST {

    private static final int INITIAL_BALANCE = 1000;
    private static final int ALICE_PORT = 8001;
    private static final int BOB_PORT = 8002;
    private static final int EXPECTED_TOTAL = INITIAL_BALANCE * 2;
    private static final int NUM_TRANSFERS = 20;

    public void run() throws IOException {
        // Start the two account servers
        startNode("alice", "10.0.0.1", this::alice);
        startNode("bob", "10.0.0.2", this::bob);

        // Start the coordinator that performs transfers
        startNode("coordinator", "10.0.0.3", this::coordinator);
    }

    private Void alice() throws IOException {
        var server = new AccountServer("alice", INITIAL_BALANCE);
        Signals.ready();
        server.serve(ALICE_PORT);
        return null;
    }

    private Void bob() throws IOException {
        var server = new AccountServer("bob", INITIAL_BALANCE);
        Signals.ready();
        server.serve(BOB_PORT);
        return null;
    }

    private Void coordinator() throws InterruptedException {
        // Give the servers a moment to start listening
        Thread.sleep(100);
        Signals.ready();

        var random = new Random();

        for (int i = 0; i < NUM_TRANSFERS; i++) {
            // Pick a random direction and amount
            boolean aliceToBob = random.nextBoolean();
            int amount = random.nextInt(1, 101); // 1-100

            String fromHost = aliceToBob ? "10.0.0.1" : "10.0.0.2";
            int fromPort = aliceToBob ? ALICE_PORT : BOB_PORT;
            String toHost = aliceToBob ? "10.0.0.2" : "10.0.0.1";
            int toPort = aliceToBob ? BOB_PORT : ALICE_PORT;

            var svc = new TransferService(fromHost, fromPort, toHost, toPort);
            svc.transfer(amount);

            // After each transfer, check the invariant
            checkInvariant();

            // Small delay between transfers
            Thread.sleep(50);
        }

        // Final invariant check
        checkInvariant();

        return null;
    }

    /**
     * Queries both account servers and asserts that total money is conserved.
     */
    private void checkInvariant() {
        try {
            int aliceBalance = queryBalance("10.0.0.1", ALICE_PORT);
            int bobBalance = queryBalance("10.0.0.2", BOB_PORT);
            int total = aliceBalance + bobBalance;

            Assert.always(
                    total == EXPECTED_TOTAL,
                    "total balance conserved",
                    Map.of("alice", aliceBalance, "bob", bobBalance, "total", total, "expected", EXPECTED_TOTAL));

            Assert.always(
                    aliceBalance >= 0 && bobBalance >= 0,
                    "no negative balance",
                    Map.of("alice", aliceBalance, "bob", bobBalance));

        } catch (IOException e) {
            // Can't check the invariant if we can't reach the servers —
            // this is fine, it just means the network is partitioned
            System.out.printf("[checker] Could not check invariant: %s%n", e.getMessage());
        }
    }

    private int queryBalance(String host, int port) throws IOException {
        try (var socket = new Socket(host, port)) {
            var dataOut = new DataOutputStream(socket.getOutputStream());
            dataOut.writeInt(AccountServer.CMD_BALANCE);
            dataOut.writeInt(0); // amount is unused for BALANCE
            dataOut.flush();

            var in = new DataInputStream(socket.getInputStream());
            int status = in.readInt();
            return in.readInt(); // the balance
        }
    }
}
