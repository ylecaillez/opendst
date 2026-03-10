/******************************************************************************
 * Socket.p
 *
 * Models a Java Socket.
 *
 * A Socket can be either:
 *   - Client-side: created by the user, connects to a remote address
 *   - Accepted-side: created by the Network when a connection is accepted
 *
 * Lifecycle (client):
 *   Unconnected --connect()--> Connecting --> Connected --> [shutdowns/close]
 *
 * Lifecycle (accepted):
 *   Init --> WaitForChannel --> Connected --> [shutdowns/close]
 *
 * Connected sub-states (tracked via flags):
 *   - inputShutdown: read() returns EOF locally
 *   - outputShutdown: write() throws IOException (documented by shutdownOutput() Javadoc)
 *   - peerEOF: peer called shutdownOutput or close; read returns EOF
 *   - peerReset: peer closed abruptly; read/write throw IOException
 *
 * Modeling principle: We model ONLY what the Java SE 17 Javadoc explicitly
 * guarantees. Where the Javadoc says only "Throws: IOException - if an I/O
 * error occurs", we model the exception as nondeterministic (using $)
 * rather than inferring specific preconditions from the implementation.
 *
 * Documented Java behaviors modeled:
 *   - write() after close() -> IOException (close() doc: "not available for further networking use")
 *   - write() after shutdownOutput() -> IOException (shutdownOutput() doc: "stream will throw an IOException")
 *   - read() after close() -> IOException (getInputStream() doc: "the socket is closed")
 *   - read() after shutdownInput() -> EOF/-1 (shutdownInput() doc: "read methods will return -1")
 *   - read() when peer has shutdown output -> EOF (implied by TCP + shutdownOutput() doc)
 *   - read() on broken connection -> IOException (getInputStream() doc: broken connection detection)
 *   - write() on broken connection -> nondeterministic IOException (Javadoc is generic for write;
 *       getOutputStream() and OutputStream.write() say nothing about broken connections, unlike
 *       getInputStream() which explicitly documents read behavior on broken connections)
 *   - close() is idempotent (Closeable contract)
 *   - close() can throw IOException (Javadoc: "if an I/O error occurs")
 *   - connect() on closed socket -> IOException (close() doc: "can't be reconnected")
 *   - shutdownOutput()/shutdownInput() -> nondeterministic IOException (Javadoc is generic)
 ******************************************************************************/

machine SocketMachine
{
    var role: tSocketRole;
    var network: machine;
    var channel: machine;

    // Flags tracking shutdown/EOF/reset state
    var outputShutdown: bool;
    var inputShutdown: bool;
    var peerEOF: bool;
    var peerReset: bool;

    // Receive buffer: data delivered by the network but not yet read by user
    var recvBuffer: seq[seq[int]];

    start state Init {
        entry (params: (role: tSocketRole, network: machine, networkChannel: machine)) {
            role = params.role;
            network = params.network;

            if (role == SOCKET_ACCEPTED) {
                // Accepted sockets go to a waiting state for their channel assignment
                goto WaitForChannel;
            } else {
                // Client sockets start unconnected
                goto Unconnected;
            }
        }
    }

    /***********************************************
     * Accepted-side: wait for channel assignment
     ***********************************************/
    state WaitForChannel {
        on eInternalSetChannel do (payload: (ch: machine)) {
            channel = payload.ch;
            goto Connected;
        }

        // Absorb user events while not yet wired up (shouldn't normally happen)
        on eSocketWrite do (payload: (caller: machine, dat: seq[int])) {
            send payload.caller, eSocketWriteResp, (status = IO_ERROR,);
            announce eSpec_IOExceptionRaised, (socket = this, operation = "write");
        }
        on eSocketRead do (payload: (caller: machine)) {
            send payload.caller, eSocketReadResp, (status = IO_ERROR, dat = default(seq[int]));
            announce eSpec_IOExceptionRaised, (socket = this, operation = "read");
        }
        on eSocketClose do (payload: (caller: machine)) {
            // Javadoc: "Throws: IOException - if an I/O error occurs when closing this socket."
            if ($) {
                send payload.caller, eSocketCloseResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "close");
            } else {
                send payload.caller, eSocketCloseResp, (status = IO_SUCCESS,);
                announce eSpec_SocketClosed, (socket = this,);
                goto Closed;
            }
        }
    }

    /***********************************************
     * Client-side: unconnected
     ***********************************************/
    state Unconnected {
        on eSocketConnect do (payload: (caller: machine, remoteAddr: tSocketAddress)) {
            // Ask the network to establish a connection
            send network, eNetConnect, (clientSocket = this, remoteAddr = payload.remoteAddr);
            receive {
                case eNetConnectResult: (resp: (status: tConnectStatus, networkChannel: machine)) {
                    if (resp.status == CONNECT_SUCCESS) {
                        channel = resp.networkChannel;
                        send payload.caller, eSocketConnectResp, (status = CONNECT_SUCCESS,);
                        goto Connected;
                    } else if (resp.status == CONNECT_REFUSED) {
                        send payload.caller, eSocketConnectResp, (status = CONNECT_REFUSED,);
                        // Stay in Unconnected — can retry
                    } else {
                        send payload.caller, eSocketConnectResp, (status = CONNECT_IOERROR,);
                    }
                }
            }
        }

        on eSocketWrite do (payload: (caller: machine, dat: seq[int])) {
            // Not connected — IOException
            send payload.caller, eSocketWriteResp, (status = IO_ERROR,);
            announce eSpec_IOExceptionRaised, (socket = this, operation = "write");
        }

        on eSocketRead do (payload: (caller: machine)) {
            // Not connected — IOException
            send payload.caller, eSocketReadResp, (status = IO_ERROR, dat = default(seq[int]));
            announce eSpec_IOExceptionRaised, (socket = this, operation = "read");
        }

        on eSocketShutdownOutput do (payload: (caller: machine)) {
            // Javadoc: "Throws: IOException - if an I/O error occurs"
            // Not connected — Javadoc doesn't explicitly list this as a precondition.
            // Model as nondeterministic.
            if ($) {
                send payload.caller, eSocketShutdownOutputResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "shutdownOutput");
            } else {
                send payload.caller, eSocketShutdownOutputResp, (status = IO_SUCCESS,);
            }
        }

        on eSocketShutdownInput do (payload: (caller: machine)) {
            if ($) {
                send payload.caller, eSocketShutdownInputResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "shutdownInput");
            } else {
                send payload.caller, eSocketShutdownInputResp, (status = IO_SUCCESS,);
            }
        }

        on eSocketClose do (payload: (caller: machine)) {
            // Javadoc: "Throws: IOException - if an I/O error occurs when closing this socket."
            if ($) {
                send payload.caller, eSocketCloseResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "close");
            } else {
                send payload.caller, eSocketCloseResp, (status = IO_SUCCESS,);
                announce eSpec_SocketClosed, (socket = this,);
                goto Closed;
            }
        }
    }

    /***********************************************
     * Connected: main operating state
     *
     * Uses flags (outputShutdown, inputShutdown,
     * peerEOF, peerReset) to track sub-states
     * rather than an explosion of P states.
     ***********************************************/
    state Connected {
        // --- Network-delivered events (async from channel) ---

        on eNetDeliverData do (payload: (dat: seq[int])) {
            if (!inputShutdown && !peerReset) {
                // Buffer the data for the next read() call
                recvBuffer += (sizeof(recvBuffer), payload.dat);
            }
            // If inputShutdown, we silently discard (Java behavior)
        }

        on eNetDeliverEOF do {
            peerEOF = true;
        }

        on eNetDeliverReset do {
            peerReset = true;
            announce eSpec_ConnectionReset, (socket = this,);
        }

        // --- User-facing operations ---

        on eSocketWrite do (payload: (caller: machine, dat: seq[int])) {
            if (outputShutdown) {
                // shutdownOutput was called — IOException
                send payload.caller, eSocketWriteResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "write");
            } else if (peerReset) {
                // Peer reset the connection.
                // The Javadoc does NOT explicitly document write behavior on a reset
                // connection (unlike getInputStream() which explicitly says "all
                // subsequent calls to read will throw an IOException" for broken
                // connections). OutputStream.write() only has the generic
                // "IOException - if an I/O error occurs". Per our modeling principle,
                // this is nondeterministic.
                if ($) {
                    send payload.caller, eSocketWriteResp, (status = IO_ERROR,);
                    announce eSpec_IOExceptionRaised, (socket = this, operation = "write");
                } else {
                    // Nondeterministic success path: the write may appear to succeed
                    // (e.g., data buffered locally before reset is detected).
                    send channel, eNetSendData, (sender = this, dat = payload.dat);
                    send payload.caller, eSocketWriteResp, (status = IO_SUCCESS,);
                    announce eSpec_DataWritten, (socket = this, dat = payload.dat);
                }
            } else {
                // Send data to network channel
                send channel, eNetSendData, (sender = this, dat = payload.dat);
                send payload.caller, eSocketWriteResp, (status = IO_SUCCESS,);
                announce eSpec_DataWritten, (socket = this, dat = payload.dat);
            }
        }

        on eSocketRead do (payload: (caller: machine)) {
            if (inputShutdown) {
                // Javadoc for shutdownInput(): "its read methods will return -1 (end of stream)"
                // This is explicitly documented behavior.
                send payload.caller, eSocketReadResp, (status = IO_EOF, dat = default(seq[int]));
                announce eSpec_EOFRead, (socket = this,);
            } else if (peerReset) {
                // Peer reset — IOException
                send payload.caller, eSocketReadResp, (status = IO_ERROR, dat = default(seq[int]));
                announce eSpec_IOExceptionRaised, (socket = this, operation = "read");
            } else if (sizeof(recvBuffer) > 0) {
                // Data available — return it
                DequeueAndRead(payload.caller);
            } else if (peerEOF) {
                // No buffered data and peer has sent EOF — return EOF
                send payload.caller, eSocketReadResp, (status = IO_EOF, dat = default(seq[int]));
                announce eSpec_EOFRead, (socket = this,);
            } else {
                // No data available yet — block until data arrives, EOF, or reset
                ReadBlocking(payload.caller);
            }
        }

        on eSocketShutdownOutput do (payload: (caller: machine)) {
            // Javadoc: "Disables the output stream for this socket."
            // Javadoc: "Throws: IOException - if an I/O error occurs when shutting down this socket."
            // The Javadoc does NOT enumerate specific preconditions for the exception.
            // We model this as: nondeterministic IOException, or success with documented effects.
            if ($) {
                // Nondeterministic IOException — Javadoc makes no post-condition guarantee on failure
                send payload.caller, eSocketShutdownOutputResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "shutdownOutput");
            } else if (!outputShutdown) {
                // Success: disable output stream, send EOF to peer
                outputShutdown = true;
                send channel, eNetShutdownOutput, (sender = this,);
                send payload.caller, eSocketShutdownOutputResp, (status = IO_SUCCESS,);
                announce eSpec_ShutdownOutputCompleted, (socket = this,);
            } else {
                // Output already shutdown — calling again could succeed or fail;
                // Javadoc doesn't specify. We treat it as a no-op success since
                // the documented effect ("disables the output stream") is already achieved.
                send payload.caller, eSocketShutdownOutputResp, (status = IO_SUCCESS,);
            }
        }

        on eSocketShutdownInput do (payload: (caller: machine)) {
            // Javadoc: "Places the input stream for this socket at 'end of stream'."
            // Javadoc: "Throws: IOException - if an I/O error occurs when shutting down this socket."
            // Same principle: nondeterministic IOException or success with documented effects.
            if ($) {
                // Nondeterministic IOException
                send payload.caller, eSocketShutdownInputResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "shutdownInput");
            } else if (!inputShutdown) {
                // Success: place input stream at end of stream
                inputShutdown = true;
                // shutdownInput is local-only — no network notification
                // Discard any buffered data
                recvBuffer = default(seq[seq[int]]);
                send payload.caller, eSocketShutdownInputResp, (status = IO_SUCCESS,);
            } else {
                // Input already shutdown — treat as no-op success
                send payload.caller, eSocketShutdownInputResp, (status = IO_SUCCESS,);
            }
        }

        on eSocketClose do (payload: (caller: machine)) {
            // Javadoc: "Closes this socket."
            // Javadoc: "Throws: IOException - if an I/O error occurs when closing this socket."
            // close() can nondeterministically throw IOException per Javadoc.
            // On success: notify the network channel and transition to Closed.
            if ($) {
                // Nondeterministic IOException on close
                send payload.caller, eSocketCloseResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "close");
            } else {
                // Success: notify the network channel that this endpoint is closing.
                send channel, eNetReset, (sender = this,);
                send payload.caller, eSocketCloseResp, (status = IO_SUCCESS,);
                announce eSpec_SocketClosed, (socket = this,);
                goto Closed;
            }
        }

        // Javadoc doesn't explicitly document behavior of connect() on already-connected socket.
        // Model as nondeterministic IOException.
        on eSocketConnect do (payload: (caller: machine, remoteAddr: tSocketAddress)) {
            if ($) {
                send payload.caller, eSocketConnectResp, (status = CONNECT_IOERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "connect");
            } else {
                send payload.caller, eSocketConnectResp, (status = CONNECT_SUCCESS,);
            }
        }
    }

    /***********************************************
     * Closed: terminal state
     ***********************************************/
    state Closed {
        on eSocketWrite do (payload: (caller: machine, dat: seq[int])) {
            send payload.caller, eSocketWriteResp, (status = IO_ERROR,);
            announce eSpec_IOExceptionRaised, (socket = this, operation = "write");
        }

        on eSocketRead do (payload: (caller: machine)) {
            send payload.caller, eSocketReadResp, (status = IO_ERROR, dat = default(seq[int]));
            announce eSpec_IOExceptionRaised, (socket = this, operation = "read");
        }

        on eSocketShutdownOutput do (payload: (caller: machine)) {
            // Javadoc: "Throws: IOException - if an I/O error occurs"
            // Closed socket — Javadoc doesn't explicitly list this as a precondition.
            // Model as nondeterministic.
            if ($) {
                send payload.caller, eSocketShutdownOutputResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "shutdownOutput");
            } else {
                send payload.caller, eSocketShutdownOutputResp, (status = IO_SUCCESS,);
            }
        }

        on eSocketShutdownInput do (payload: (caller: machine)) {
            if ($) {
                send payload.caller, eSocketShutdownInputResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "shutdownInput");
            } else {
                send payload.caller, eSocketShutdownInputResp, (status = IO_SUCCESS,);
            }
        }

        on eSocketConnect do (payload: (caller: machine, remoteAddr: tSocketAddress)) {
            // Javadoc for close(): "Once a socket has been closed, it is not available
            // for further networking use (i.e. can't be reconnected or rebound)."
            // This is explicitly documented — always IOException.
            send payload.caller, eSocketConnectResp, (status = CONNECT_IOERROR,);
            announce eSpec_IOExceptionRaised, (socket = this, operation = "connect");
        }

        on eSocketClose do (payload: (caller: machine)) {
            // close() on already-closed is a no-op in Java
            send payload.caller, eSocketCloseResp, (status = IO_SUCCESS,);
        }

        // Absorb any late network deliveries
        ignore eNetDeliverData, eNetDeliverEOF, eNetDeliverReset, eInternalSetChannel;
    }

    /***********************************************
     * Helper functions
     ***********************************************/

    // Dequeue the first chunk from recvBuffer and return it to the caller
    fun DequeueAndRead(caller: machine) {
        var chunk: seq[int];
        chunk = recvBuffer[0];
        recvBuffer -= (0);
        send caller, eSocketReadResp, (status = IO_SUCCESS, dat = chunk);
        announce eSpec_DataRead, (socket = this, dat = chunk);
    }

    // Blocking read: suspends until data, EOF, or reset arrives from network
    fun ReadBlocking(caller: machine) {
        receive {
            case eNetDeliverData: (payload: (dat: seq[int])) {
                send caller, eSocketReadResp, (status = IO_SUCCESS, dat = payload.dat);
                announce eSpec_DataRead, (socket = this, dat = payload.dat);
            }
            case eNetDeliverEOF: {
                peerEOF = true;
                send caller, eSocketReadResp, (status = IO_EOF, dat = default(seq[int]));
                announce eSpec_EOFRead, (socket = this,);
            }
            case eNetDeliverReset: {
                peerReset = true;
                announce eSpec_ConnectionReset, (socket = this,);
                send caller, eSocketReadResp, (status = IO_ERROR, dat = default(seq[int]));
                announce eSpec_IOExceptionRaised, (socket = this, operation = "read");
            }
            case eSocketClose: (payload: (caller: machine)) {
                // Socket closed while blocking in read()
                send channel, eNetReset, (sender = this,);
                send caller, eSocketReadResp, (status = IO_ERROR, dat = default(seq[int]));
                announce eSpec_IOExceptionRaised, (socket = this, operation = "read");
                send payload.caller, eSocketCloseResp, (status = IO_SUCCESS,);
                announce eSpec_SocketClosed, (socket = this,);
                goto Closed;
            }
        }
    }
}
