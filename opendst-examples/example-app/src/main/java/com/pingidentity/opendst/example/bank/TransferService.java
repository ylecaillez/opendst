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

import static java.lang.System.out;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * A transfer service that moves money between two {@link AccountServer}s.
 *
 * <p>This implementation is intentionally naive: it debits the source account
 * first, then credits the destination. There is no two-phase commit, no
 * distributed transaction, and no compensation logic.
 *
 * <p><b>Known bugs (for OpenDST to discover):</b>
 * <ul>
 *   <li>If the credit call fails after the debit succeeds, the debited
 *       money simply vanishes (no rollback).</li>
 *   <li>If the credit call times out but actually succeeds on the server,
 *       the retry logic credits the destination a second time (double credit).</li>
 * </ul>
 */
public final class TransferService {

    /** How long to wait for a response before considering the call failed. */
    private static final int SOCKET_TIMEOUT_MS = 2000;

    /** Maximum number of retry attempts for the credit step. */
    private static final int MAX_RETRIES = 2;

    private final String sourceHost;
    private final int sourcePort;
    private final String destHost;
    private final int destPort;

    public TransferService(String sourceHost, int sourcePort, String destHost, int destPort) {
        this.sourceHost = sourceHost;
        this.sourcePort = sourcePort;
        this.destHost = destHost;
        this.destPort = destPort;
    }

    /**
     * Transfers {@code amount} from the source account to the destination account.
     *
     * @return {@code true} if the transfer appeared to succeed, {@code false} otherwise.
     */
    public boolean transfer(int amount) {
        out.printf("[transfer] Starting transfer of %d from %s to %s%n", amount, sourceHost, destHost);

        // Step 1: Debit the source account
        int debitStatus;
        try {
            debitStatus = sendCommand(sourceHost, sourcePort, AccountServer.CMD_DEBIT, amount);
        } catch (IOException e) {
            out.printf("[transfer] Debit failed (network error): %s%n", e.getMessage());
            return false;
        }

        if (debitStatus != AccountServer.STATUS_OK) {
            out.printf("[transfer] Debit refused (status=%d)%n", debitStatus);
            return false;
        }

        out.printf("[transfer] Debit of %d succeeded, now crediting destination...%n", amount);

        // Step 2: Credit the destination account
        //
        // BUG: If this fails, the money has already been debited from the source
        //      but never arrives at the destination. The money is destroyed.
        //
        // BUG: If this times out but the server actually processes the credit,
        //      the retry will credit the destination again. Money is created.
        //
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                int creditStatus = sendCommand(destHost, destPort, AccountServer.CMD_CREDIT, amount);
                if (creditStatus == AccountServer.STATUS_OK) {
                    out.printf("[transfer] Credit of %d succeeded on attempt %d%n", amount, attempt);
                    return true;
                }
                out.printf("[transfer] Credit refused (status=%d)%n", creditStatus);
                return false;
            } catch (IOException e) {
                out.printf("[transfer] Credit attempt %d failed: %s%n", attempt, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    out.printf("[transfer] Retrying credit...%n");
                }
            }
        }

        // All credit attempts failed — money has been lost
        out.printf("[transfer] All credit attempts failed. Money lost!%n");
        return false;
    }

    /**
     * Sends a command to an {@link AccountServer} and returns the response status.
     */
    private int sendCommand(String host, int port, int command, int amount) throws IOException {
        try (var socket = new Socket(host, port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            var dataOut = new DataOutputStream(socket.getOutputStream());
            dataOut.writeInt(command);
            dataOut.writeInt(amount);
            dataOut.flush();

            var in = new DataInputStream(socket.getInputStream());
            int status = in.readInt();
            int balance = in.readInt(); // read but not used (protocol compliance)
            out.printf("[transfer] Response from %s: status=%d, balance=%d%n", host, status, balance);
            return status;
        }
    }
}
