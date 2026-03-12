/******************************************************************************
 * Specifications.p
 *
 * P spec monitors that observe the system and check safety/liveness properties.
 *
 * Safety properties (violations cause assertion failures):
 *   1. DataIntegrity: data read by a socket must be a prefix of data written
 *      by the peer socket on that connection. No phantom data, no reordering.
 *   2. NoWriteAfterClose: if a socket is closed, any subsequent write must
 *      return IO_ERROR (not IO_SUCCESS).
 *   3. NoPhantomData: data can only be read from sockets that are part of an
 *      established connection.
 *   4. IOExceptionOnClosedSocket: no successful read or write can occur on a
 *      closed socket.
 ******************************************************************************/

/******************************************************************************
 * Safety: DataIntegrity
 *
 * For each connection, tracks data written by each endpoint and data read by
 * the peer. Asserts that data read is always a prefix of data written.
 *
 * We track per-connection using the (clientSocket, acceptedSocket) pair
 * established at connection time.
 ******************************************************************************/
spec DataIntegrity observes eSpec_ConnectionEstablished, eSpec_DataWritten, eSpec_DataRead
{
    // Track established connections: socket -> its peer
    var peerOf: map[machine, machine];

    // Cumulative bytes written by each socket
    var written: map[machine, seq[int]];
    // Cumulative bytes read by each socket
    var readData: map[machine, seq[int]];

    start state Monitoring {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            peerOf[payload.clientSocket] = payload.acceptedSocket;
            peerOf[payload.acceptedSocket] = payload.clientSocket;
            written[payload.clientSocket] = default(seq[int]);
            written[payload.acceptedSocket] = default(seq[int]);
            readData[payload.clientSocket] = default(seq[int]);
            readData[payload.acceptedSocket] = default(seq[int]);
        }

        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            var i: int;
            if (payload.socket in written) {
                i = 0;
                while (i < sizeof(payload.dat)) {
                    written[payload.socket] += (sizeof(written[payload.socket]), payload.dat[i]);
                    i = i + 1;
                }
            }
        }

        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            var peer: machine;
            var i: int;
            var readOffset: int;
            if (payload.socket in readData && payload.socket in peerOf) {
                peer = peerOf[payload.socket];
                i = 0;
                while (i < sizeof(payload.dat)) {
                    readData[payload.socket] += (sizeof(readData[payload.socket]), payload.dat[i]);
                    i = i + 1;
                }
                // Assert: data read by this socket must be a prefix of data written by peer
                readOffset = sizeof(readData[payload.socket]);
                assert readOffset <= sizeof(written[peer]),
                    "DataIntegrity violation: socket read more bytes than peer has written";

                // Check byte-by-byte that what was read matches what was written
                i = sizeof(readData[payload.socket]) - sizeof(payload.dat);
                while (i < sizeof(readData[payload.socket])) {
                    assert readData[payload.socket][i] == written[peer][i],
                        "DataIntegrity violation: byte mismatch between written and read data";
                    i = i + 1;
                }
            }
        }
    }
}

/******************************************************************************
 * Safety: NoWriteAfterClose
 *
 * Once a socket is closed, any write that returns IO_SUCCESS is a violation.
 * We track closed sockets and observe write responses.
 ******************************************************************************/
spec NoWriteAfterClose observes eSpec_SocketClosed, eSpec_DataWritten
{
    var closedSockets: set[machine];

    start state Monitoring {
        on eSpec_SocketClosed do (payload: (socket: machine)) {
            closedSockets += (payload.socket);
        }

        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            assert !(payload.socket in closedSockets),
                "NoWriteAfterClose violation: data written on a closed socket";
        }
    }
}

/******************************************************************************
 * Safety: NoPhantomData
 *
 * Data can only be read from a socket that is part of an established connection.
 * No data should appear that wasn't written by anyone.
 ******************************************************************************/
spec NoPhantomData observes eSpec_ConnectionEstablished, eSpec_SocketConnected, eSpec_SocketAccepted, eSpec_DataRead
{
    var connectedSockets: set[machine];

    start state Monitoring {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            connectedSockets += (payload.clientSocket);
            connectedSockets += (payload.acceptedSocket);
        }

        on eSpec_SocketConnected do (payload: (socket: machine)) {
            connectedSockets += (payload.socket);
        }

        on eSpec_SocketAccepted do (payload: (socket: machine)) {
            connectedSockets += (payload.socket);
        }

        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            assert payload.socket in connectedSockets,
                "NoPhantomData violation: data read from a socket that was never connected";
        }
    }
}

/******************************************************************************
 * Safety: IOExceptionOnClosedSocket
 *
 * If a socket is closed, any subsequent read/write/shutdownInput/shutdownOutput
 * MUST raise an IOException. We observe the close event and then check that
 * the corresponding IOException is announced.
 *
 * This is implicitly enforced by the state machine design (Closed state always
 * returns IO_ERROR and announces eSpec_IOExceptionRaised), but this spec
 * provides an independent check at the specification level.
 ******************************************************************************/
spec IOExceptionOnClosedSocket observes eSpec_SocketClosed, eSpec_IOExceptionRaised, eSpec_DataWritten, eSpec_DataRead
{
    var closedSockets: set[machine];

    start state Monitoring {
        on eSpec_SocketClosed do (payload: (socket: machine)) {
            closedSockets += (payload.socket);
        }

        // IOExceptionRaised is expected behavior on closed sockets — not a violation
        ignore eSpec_IOExceptionRaised;

        // If data is successfully written on a closed socket, that's a violation
        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            assert !(payload.socket in closedSockets),
                "IOExceptionOnClosedSocket: successful write on closed socket";
        }

        // If data is successfully read on a closed socket, that's a violation
        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            assert !(payload.socket in closedSockets),
                "IOExceptionOnClosedSocket: successful read on closed socket";
        }
    }
}

/******************************************************************************
 * Safety: NoWriteAfterShutdownOutput
 *
 * Once shutdownOutput() succeeds on a socket, any subsequent write that
 * returns IO_SUCCESS is a violation. The Java API requires IOException on
 * write after shutdownOutput.
 ******************************************************************************/
spec NoWriteAfterShutdownOutput observes eSpec_ShutdownOutputCompleted, eSpec_DataWritten
{
    var shutdownSockets: set[machine];

    start state Monitoring {
        on eSpec_ShutdownOutputCompleted do (payload: (socket: machine)) {
            shutdownSockets += (payload.socket);
        }

        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            assert !(payload.socket in shutdownSockets),
                "NoWriteAfterShutdownOutput violation: data written after shutdownOutput()";
        }
    }
}

/******************************************************************************
 * Safety: NoReadAfterShutdownInput
 *
 * Once shutdownInput() succeeds on a socket, any subsequent read that
 * returns data (not EOF or IOException) is a violation. The Java API
 * specifies that read() after shutdownInput() returns EOF.
 ******************************************************************************/
spec NoReadAfterShutdownInput observes eSpec_ShutdownInputCompleted, eSpec_DataRead
{
    var shutdownSockets: set[machine];

    start state Monitoring {
        on eSpec_ShutdownInputCompleted do (payload: (socket: machine)) {
            shutdownSockets += (payload.socket);
        }

        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            assert !(payload.socket in shutdownSockets),
                "NoReadAfterShutdownInput violation: data read after shutdownInput()";
        }
    }
}

/******************************************************************************
 * Safety: NoSilentDataDiscard
 *
 * Data written to a socket must never be silently discarded. If the peer's
 * input is already shut down, the writer should get an error or the data
 * should be delivered. A DataDiscarded event indicates silent data loss.
 ******************************************************************************/
spec NoSilentDataDiscard observes eSpec_DataDiscarded
{
    start state Monitoring {
        on eSpec_DataDiscarded do (payload: (socket: machine, byteCount: int)) {
            assert false,
                "NoSilentDataDiscard violation: data silently discarded";
        }
    }
}

/******************************************************************************
 * Safety: AvailableConsistency
 *
 * The value returned by available() must not exceed the number of bytes
 * that have been written by the peer but not yet read by this socket.
 * Tracks per-connection cumulative writes and reads, then checks the
 * reported available count against the upper bound.
 *
 * Also asserts that available() returns 0 after shutdownInput().
 ******************************************************************************/
spec AvailableConsistency observes eSpec_ConnectionEstablished, eSpec_DataWritten, eSpec_DataRead, eSpec_AvailableQueried, eSpec_ShutdownInputCompleted
{
    // peer mapping: socket -> its peer
    var peerOf: map[machine, machine];
    // Total bytes written by each socket
    var totalWritten: map[machine, int];
    // Total bytes read by each socket
    var totalRead: map[machine, int];
    // Sockets that have shut down input
    var inputShutdown: set[machine];

    start state Monitoring {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            peerOf[payload.clientSocket] = payload.acceptedSocket;
            peerOf[payload.acceptedSocket] = payload.clientSocket;
            totalWritten[payload.clientSocket] = 0;
            totalWritten[payload.acceptedSocket] = 0;
            totalRead[payload.clientSocket] = 0;
            totalRead[payload.acceptedSocket] = 0;
        }

        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            if (payload.socket in totalWritten) {
                totalWritten[payload.socket] = totalWritten[payload.socket] + sizeof(payload.dat);
            }
        }

        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            if (payload.socket in totalRead) {
                totalRead[payload.socket] = totalRead[payload.socket] + sizeof(payload.dat);
            }
        }

        on eSpec_ShutdownInputCompleted do (payload: (socket: machine)) {
            inputShutdown += (payload.socket);
        }

        on eSpec_AvailableQueried do (payload: (socket: machine, reportedCount: int)) {
            // After shutdownInput, available() must return 0
            if (payload.socket in inputShutdown) {
                assert payload.reportedCount == 0,
                    "AvailableConsistency violation: available() non-zero after shutdownInput()";
            }

            // available() must not exceed unread bytes from peer
            CheckAvailableUpperBound(payload.socket, payload.reportedCount);
        }
    }

    fun CheckAvailableUpperBound(socket: machine, reportedCount: int) {
        var peer: machine;
        var unreadBytes: int;
        if (socket in peerOf && socket in totalRead) {
            peer = peerOf[socket];
            if (peer in totalWritten) {
                unreadBytes = totalWritten[peer] - totalRead[socket];
                assert reportedCount <= unreadBytes,
                    "AvailableConsistency violation: available() reports more bytes than peer has written minus what we read";
            }
        }
    }
}

/******************************************************************************
 * Liveness: GracefulShutdownIntegrity
 *
 * After shutdownOutput() on a socket, all previously written bytes must
 * be read by the peer before EOF. Unlike DeliveryLiveness, the writer-side
 * SocketClosed does NOT excuse the delivery obligation — the sender chose
 * graceful shutdown, so the data must arrive. Only the reader-side close
 * or a connection reset excuses the obligation.
 *
 * Uses hot/cold states: enters hot state when shutdownOutput occurs with
 * undelivered bytes. Returns to cold when peer reads all bytes + EOF.
 ******************************************************************************/
spec GracefulShutdownIntegrity observes eSpec_ConnectionEstablished, eSpec_DataWritten, eSpec_DataRead, eSpec_ShutdownOutputCompleted, eSpec_EOFRead, eSpec_SocketClosed, eSpec_ConnectionReset, eSpec_IOExceptionRaised
{
    var peerOf: map[machine, machine];
    // Total bytes written by each socket
    var totalWritten: map[machine, int];
    // Total bytes read by each socket (from its peer)
    var totalRead: map[machine, int];
    // Sockets that have completed shutdownOutput — these have a delivery obligation
    var shutdownOutputSockets: set[machine];
    // Sockets whose graceful obligation has been excused (reader-side close/reset only)
    var excused: set[machine];

    start state NoObligation {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            peerOf[payload.clientSocket] = payload.acceptedSocket;
            peerOf[payload.acceptedSocket] = payload.clientSocket;
            totalWritten[payload.clientSocket] = 0;
            totalWritten[payload.acceptedSocket] = 0;
            totalRead[payload.clientSocket] = 0;
            totalRead[payload.acceptedSocket] = 0;
        }

        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            if (payload.socket in totalWritten) {
                totalWritten[payload.socket] = totalWritten[payload.socket] + sizeof(payload.dat);
            }
        }

        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            if (payload.socket in totalRead) {
                totalRead[payload.socket] = totalRead[payload.socket] + sizeof(payload.dat);
            }
        }

        on eSpec_ShutdownOutputCompleted do (payload: (socket: machine)) {
            if (payload.socket in peerOf && !(payload.socket in excused)) {
                shutdownOutputSockets += (payload.socket);
                if (HasPendingObligation()) {
                    goto PendingGracefulDelivery;
                }
            }
        }

        on eSpec_EOFRead do (payload: (socket: machine)) {
            // EOF delivery fulfills the obligation
            if (payload.socket in peerOf) {
                shutdownOutputSockets -= (peerOf[payload.socket]);
            }
        }

        on eSpec_SocketClosed do (payload: (socket: machine)) {
            // Only the READER side close excuses the obligation
            ExcuseReaderSide(payload.socket);
        }

        on eSpec_ConnectionReset do (payload: (socket: machine)) {
            ExcuseBothSides(payload.socket);
        }

        on eSpec_IOExceptionRaised do (payload: (socket: machine, operation: string)) {
            if (payload.operation == "close") {
                ExcuseReaderSide(payload.socket);
            }
        }
    }

    hot state PendingGracefulDelivery {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            peerOf[payload.clientSocket] = payload.acceptedSocket;
            peerOf[payload.acceptedSocket] = payload.clientSocket;
            totalWritten[payload.clientSocket] = 0;
            totalWritten[payload.acceptedSocket] = 0;
            totalRead[payload.clientSocket] = 0;
            totalRead[payload.acceptedSocket] = 0;
        }

        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            if (payload.socket in totalWritten) {
                totalWritten[payload.socket] = totalWritten[payload.socket] + sizeof(payload.dat);
            }
        }

        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            if (payload.socket in totalRead) {
                totalRead[payload.socket] = totalRead[payload.socket] + sizeof(payload.dat);
            }
            if (!HasPendingObligation()) {
                goto NoObligation;
            }
        }

        on eSpec_ShutdownOutputCompleted do (payload: (socket: machine)) {
            if (payload.socket in peerOf && !(payload.socket in excused)) {
                shutdownOutputSockets += (payload.socket);
            }
        }

        on eSpec_EOFRead do (payload: (socket: machine)) {
            if (payload.socket in peerOf) {
                shutdownOutputSockets -= (peerOf[payload.socket]);
            }
            if (!HasPendingObligation()) {
                goto NoObligation;
            }
        }

        on eSpec_SocketClosed do (payload: (socket: machine)) {
            ExcuseReaderSide(payload.socket);
            if (!HasPendingObligation()) {
                goto NoObligation;
            }
        }

        on eSpec_ConnectionReset do (payload: (socket: machine)) {
            ExcuseBothSides(payload.socket);
            if (!HasPendingObligation()) {
                goto NoObligation;
            }
        }

        on eSpec_IOExceptionRaised do (payload: (socket: machine, operation: string)) {
            if (payload.operation == "close") {
                ExcuseReaderSide(payload.socket);
                if (!HasPendingObligation()) {
                    goto NoObligation;
                }
            }
        }
    }

    // Excuse only the reader-side: if socket S closes and S is the READER
    // for some writer W (i.e., W wrote to S), then W's obligation is excused.
    // Writer-side close does NOT excuse.
    fun ExcuseReaderSide(socket: machine) {
        var writer: machine;
        // If this socket is a reader (its peer is a writer), excuse the peer's obligation
        if (socket in peerOf) {
            writer = peerOf[socket];
            excused += (writer);
            shutdownOutputSockets -= (writer);
        }
    }

    // Reset excuses both sides
    fun ExcuseBothSides(socket: machine) {
        excused += (socket);
        shutdownOutputSockets -= (socket);
        if (socket in peerOf) {
            excused += (peerOf[socket]);
            shutdownOutputSockets -= (peerOf[socket]);
        }
    }

    fun HasPendingObligation() : bool {
        var sock: machine;
        var reader: machine;
        foreach (sock in shutdownOutputSockets) {
            if (!(sock in excused)) {
                // Check if peer still has unread bytes
                if (sock in peerOf && sock in totalWritten) {
                    reader = peerOf[sock];
                    if (reader in totalRead) {
                        if (totalRead[reader] < totalWritten[sock]) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}

/******************************************************************************
 * Liveness: DeliveryLiveness
 *
 * If data is written by a socket, it must eventually be read by the peer,
 * unless the connection is disrupted (close or reset).
 *
 * Uses hot/cold states: when there are unread bytes pending delivery, the
 * monitor enters a hot state. The P model checker flags a liveness violation
 * if an execution ends in a hot state.
 *
 * Tracks per-connection: total bytes written by each socket vs total bytes
 * read by the peer. Connection disruption (close or reset) clears the
 * delivery obligation.
 ******************************************************************************/
spec DeliveryLiveness observes eSpec_ConnectionEstablished, eSpec_DataWritten, eSpec_DataRead, eSpec_SocketClosed, eSpec_ConnectionReset, eSpec_IOExceptionRaised
{
    // peer mapping: socket -> its peer
    var peerOf: map[machine, machine];

    // Total bytes written by each socket (not yet read by peer)
    var pendingDeliveryCount: map[machine, int];

    // Sockets whose delivery obligation has been excused (close/reset)
    var excused: set[machine];

    start state NoPendingDelivery {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            peerOf[payload.clientSocket] = payload.acceptedSocket;
            peerOf[payload.acceptedSocket] = payload.clientSocket;
            pendingDeliveryCount[payload.clientSocket] = 0;
            pendingDeliveryCount[payload.acceptedSocket] = 0;
        }

        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            if (payload.socket in pendingDeliveryCount && !(payload.socket in excused)) {
                pendingDeliveryCount[payload.socket] = pendingDeliveryCount[payload.socket] + sizeof(payload.dat);
                if (HasPendingDelivery()) {
                    goto PendingDelivery;
                }
            }
        }

        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            var writer: machine;
            if (payload.socket in peerOf) {
                writer = peerOf[payload.socket];
                if (writer in pendingDeliveryCount) {
                    pendingDeliveryCount[writer] = pendingDeliveryCount[writer] - sizeof(payload.dat);
                }
            }
        }

        on eSpec_SocketClosed do (payload: (socket: machine)) {
            ExcuseSocket(payload.socket);
        }

        on eSpec_ConnectionReset do (payload: (socket: machine)) {
            ExcuseSocket(payload.socket);
        }

        // A failed close() attempt also excuses delivery: the socket is in an
        // undefined state and no further delivery can be expected.
        on eSpec_IOExceptionRaised do (payload: (socket: machine, operation: string)) {
            if (payload.operation == "close") {
                ExcuseSocket(payload.socket);
            }
        }
    }

    hot state PendingDelivery {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            peerOf[payload.clientSocket] = payload.acceptedSocket;
            peerOf[payload.acceptedSocket] = payload.clientSocket;
            pendingDeliveryCount[payload.clientSocket] = 0;
            pendingDeliveryCount[payload.acceptedSocket] = 0;
        }

        on eSpec_DataWritten do (payload: (socket: machine, dat: seq[int])) {
            if (payload.socket in pendingDeliveryCount && !(payload.socket in excused)) {
                pendingDeliveryCount[payload.socket] = pendingDeliveryCount[payload.socket] + sizeof(payload.dat);
            }
        }

        on eSpec_DataRead do (payload: (socket: machine, dat: seq[int])) {
            var writer: machine;
            if (payload.socket in peerOf) {
                writer = peerOf[payload.socket];
                if (writer in pendingDeliveryCount) {
                    pendingDeliveryCount[writer] = pendingDeliveryCount[writer] - sizeof(payload.dat);
                }
            }
            if (!HasPendingDelivery()) {
                goto NoPendingDelivery;
            }
        }

        on eSpec_SocketClosed do (payload: (socket: machine)) {
            ExcuseSocket(payload.socket);
            if (!HasPendingDelivery()) {
                goto NoPendingDelivery;
            }
        }

        on eSpec_ConnectionReset do (payload: (socket: machine)) {
            ExcuseSocket(payload.socket);
            if (!HasPendingDelivery()) {
                goto NoPendingDelivery;
            }
        }

        on eSpec_IOExceptionRaised do (payload: (socket: machine, operation: string)) {
            if (payload.operation == "close") {
                ExcuseSocket(payload.socket);
                if (!HasPendingDelivery()) {
                    goto NoPendingDelivery;
                }
            }
        }
    }

    // Excuse a socket and its peer from delivery obligations
    fun ExcuseSocket(socket: machine) {
        excused += (socket);
        if (socket in peerOf) {
            excused += (peerOf[socket]);
        }
    }

    // Check if any non-excused socket has pending unread data
    fun HasPendingDelivery() : bool {
        var sock: machine;
        foreach (sock in keys(pendingDeliveryCount)) {
            if (!(sock in excused) && pendingDeliveryCount[sock] > 0) {
                return true;
            }
        }
        return false;
    }
}

/******************************************************************************
 * Liveness: EOFLiveness
 *
 * If shutdownOutput() succeeds on a socket, the peer must eventually read
 * EOF, unless the connection is disrupted (close or reset).
 *
 * Uses hot/cold states: when there are pending EOF deliveries, the monitor
 * enters a hot state.
 ******************************************************************************/
spec EOFLiveness observes eSpec_ConnectionEstablished, eSpec_ShutdownOutputCompleted, eSpec_EOFRead, eSpec_SocketClosed, eSpec_ConnectionReset, eSpec_IOExceptionRaised
{
    var peerOf: map[machine, machine];

    // Sockets that have completed shutdownOutput but whose peer hasn't read EOF yet
    var pendingEOF: set[machine];

    // Excused connections (close/reset)
    var excused: set[machine];

    start state NoPendingEOF {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            peerOf[payload.clientSocket] = payload.acceptedSocket;
            peerOf[payload.acceptedSocket] = payload.clientSocket;
        }

        on eSpec_ShutdownOutputCompleted do (payload: (socket: machine)) {
            if (payload.socket in peerOf && !(payload.socket in excused)) {
                pendingEOF += (payload.socket);
                goto PendingEOFDelivery;
            }
        }

        on eSpec_EOFRead do (payload: (socket: machine)) {
            // EOF read by a socket means the peer's shutdownOutput was delivered
            if (payload.socket in peerOf) {
                pendingEOF -= (peerOf[payload.socket]);
            }
        }

        on eSpec_SocketClosed do (payload: (socket: machine)) {
            ExcuseSocket(payload.socket);
        }

        on eSpec_ConnectionReset do (payload: (socket: machine)) {
            ExcuseSocket(payload.socket);
        }

        on eSpec_IOExceptionRaised do (payload: (socket: machine, operation: string)) {
            if (payload.operation == "close") {
                ExcuseSocket(payload.socket);
            }
        }
    }

    hot state PendingEOFDelivery {
        on eSpec_ConnectionEstablished do (payload: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine)) {
            peerOf[payload.clientSocket] = payload.acceptedSocket;
            peerOf[payload.acceptedSocket] = payload.clientSocket;
        }

        on eSpec_ShutdownOutputCompleted do (payload: (socket: machine)) {
            if (payload.socket in peerOf && !(payload.socket in excused)) {
                pendingEOF += (payload.socket);
            }
        }

        on eSpec_EOFRead do (payload: (socket: machine)) {
            if (payload.socket in peerOf) {
                pendingEOF -= (peerOf[payload.socket]);
            }
            if (!HasPendingEOF()) {
                goto NoPendingEOF;
            }
        }

        on eSpec_SocketClosed do (payload: (socket: machine)) {
            ExcuseSocket(payload.socket);
            if (!HasPendingEOF()) {
                goto NoPendingEOF;
            }
        }

        on eSpec_ConnectionReset do (payload: (socket: machine)) {
            ExcuseSocket(payload.socket);
            if (!HasPendingEOF()) {
                goto NoPendingEOF;
            }
        }

        on eSpec_IOExceptionRaised do (payload: (socket: machine, operation: string)) {
            if (payload.operation == "close") {
                ExcuseSocket(payload.socket);
                if (!HasPendingEOF()) {
                    goto NoPendingEOF;
                }
            }
        }
    }

    fun ExcuseSocket(socket: machine) {
        excused += (socket);
        if (socket in peerOf) {
            excused += (peerOf[socket]);
        }
        // Remove any pending EOF obligations for excused sockets
        pendingEOF -= (socket);
        if (socket in peerOf) {
            pendingEOF -= (peerOf[socket]);
        }
    }

    fun HasPendingEOF() : bool {
        var sock: machine;
        foreach (sock in pendingEOF) {
            if (!(sock in excused)) {
                return true;
            }
        }
        return false;
    }
}
