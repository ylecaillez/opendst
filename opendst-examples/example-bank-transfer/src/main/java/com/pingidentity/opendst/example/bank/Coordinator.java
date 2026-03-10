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

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.Random;

/**
 * Coordinator that performs random transfers between two {@link AccountServer}s
 * and checks system invariants after each transfer.
 *
 * <p>After each transfer the coordinator queries both account balances and
 * asserts:
 * <ul>
 *   <li>Total money is conserved (alice + bob == initial total)</li>
 *   <li>No account has a negative balance</li>
 * </ul>
 *
 * <p>Under normal conditions these invariants always hold. When OpenDST injects
 * network faults (latency, partitions, connection resets), the naive
 * {@link TransferService} breaks:
 * <ul>
 *   <li>Money is destroyed when a credit fails after a successful debit</li>
 *   <li>Money is created when a timed-out credit is retried but the
 *       original actually succeeded</li>
 * </ul>
 */
public final class Coordinator {

    private static final int INITIAL_BALANCE = 1000;
    private static final int EXPECTED_TOTAL = INITIAL_BALANCE * 2;

    /**
     * Entry point for the deployment descriptor.
     *
     * @param args {@code [aliceHost, alicePort, bobHost, bobPort, numTransfers]}
     */
    public static void main(String[] args) throws InterruptedException {
        if (args.length < 5) {
            err.println("Usage: Coordinator <aliceHost> <alicePort> <bobHost> <bobPort> <numTransfers>");
            exit(1);
        }
        var aliceHost = args[0];
        var alicePort = parseInt(args[1]);
        var bobHost = args[2];
        var bobPort = parseInt(args[3]);
        var numTransfers = parseInt(args[4]);

        // Give the account servers a moment to start listening
        Thread.sleep(100);
        Signals.ready();

        var random = new Random();

        for (int i = 0; i < numTransfers; i++) {
            // Pick a random direction and amount
            boolean aliceToBob = random.nextBoolean();
            int amount = random.nextInt(1, 101); // 1–100

            String fromHost = aliceToBob ? aliceHost : bobHost;
            int fromPort = aliceToBob ? alicePort : bobPort;
            String toHost = aliceToBob ? bobHost : aliceHost;
            int toPort = aliceToBob ? bobPort : alicePort;

            var svc = new TransferService(fromHost, fromPort, toHost, toPort);
            svc.transfer(amount);

            // After each transfer, check the invariant
            checkInvariant(aliceHost, alicePort, bobHost, bobPort);

            // Small delay between transfers
            Thread.sleep(50);
        }

        // Final invariant check
        checkInvariant(aliceHost, alicePort, bobHost, bobPort);
    }

    /**
     * Queries both account servers and asserts that total money is conserved.
     */
    private static void checkInvariant(String aliceHost, int alicePort, String bobHost, int bobPort) {
        try {
            int aliceBalance = queryBalance(aliceHost, alicePort);
            int bobBalance = queryBalance(bobHost, bobPort);
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
            out.printf("[checker] Could not check invariant: %s%n", e.getMessage());
        }
    }

    private static int queryBalance(String host, int port) throws IOException {
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
