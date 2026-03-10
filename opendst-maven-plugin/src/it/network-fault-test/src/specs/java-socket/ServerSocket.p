/******************************************************************************
 * ServerSocket.p
 *
 * Models a Java ServerSocket.
 *
 * Lifecycle:
 *   Unbound  --bind()--> Listening  --close()--> Closed
 *
 * In the Listening state, accept() blocks (via receive) until a connection
 * arrives from the Network. Multiple connections can be queued.
 *
 * Modeling principle: We model ONLY what the Java SE 17 Javadoc explicitly
 * guarantees. Where the Javadoc says only "Throws: IOException", we model
 * the exception as nondeterministic rather than inferring preconditions.
 *
 * Documented Java behaviors modeled:
 *   - bind() fails with IOException if already bound (Javadoc: "or if the socket is already bound")
 *   - bind() fails with IOException if address in use (Javadoc: "if the bind operation fails")
 *   - accept() blocks until a connection arrives (Javadoc: "The method blocks until a connection is made")
 *   - close() interrupts blocked accept() with SocketException (Javadoc: documented)
 *   - close() on already-closed ServerSocket is a no-op (Closeable contract)
 *   - close() can throw IOException (Javadoc: "if an I/O error occurs when closing the socket")
 ******************************************************************************/

machine ServerSocketMachine
{
    var network: machine;
    var boundAddr: tSocketAddress;

    // Queue of accepted sockets waiting to be returned by accept()
    var pendingConnections: seq[machine];

    start state Init {
        entry (params: (net: machine)) {
            network = params.net;
            goto Unbound;
        }
    }

    state Unbound {
        on eServerBind do (payload: (caller: machine, addr: tSocketAddress)) {
            boundAddr = payload.addr;
            // Ask the network to register us as a listener
            send network, eNetListen, (serverSocket = this, addr = boundAddr);
            receive {
                case eNetListenResp: (resp: (status: tIOResult)) {
                    if (resp.status == IO_SUCCESS) {
                        send payload.caller, eServerBindResp, (status = IO_SUCCESS,);
                        goto Listening;
                    } else {
                        // Address already in use
                        send payload.caller, eServerBindResp, (status = IO_ERROR,);
                    }
                }
            }
        }

        on eServerAccept do (payload: (caller: machine)) {
            // Javadoc: "Throws: IOException - if an I/O error occurs when waiting for a connection."
            // Not bound — Javadoc doesn't explicitly list this as a precondition.
            // Model as nondeterministic.
            if ($) {
                send payload.caller, eServerAcceptResp,
                    (status = IO_ERROR, acceptedSocket = default(machine));
                announce eSpec_IOExceptionRaised, (socket = this, operation = "accept");
            } else {
                send payload.caller, eServerAcceptResp,
                    (status = IO_SUCCESS, acceptedSocket = default(machine));
            }
        }

        on eServerClose do (payload: (caller: machine)) {
            // Javadoc: "Throws: IOException - if an I/O error occurs when closing the socket."
            if ($) {
                send payload.caller, eServerCloseResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "close");
            } else {
                send payload.caller, eServerCloseResp, (status = IO_SUCCESS,);
                announce eSpec_SocketClosed, (socket = this,);
                goto Closed;
            }
        }
    }

    state Listening {
        // Incoming connections from the network are queued
        on eNetIncomingConnection do (payload: (clientSocket: machine, acceptedSocket: machine)) {
            pendingConnections += (sizeof(pendingConnections), payload.acceptedSocket);
        }

        on eServerAccept do (payload: (caller: machine)) {
            if (sizeof(pendingConnections) > 0) {
                // Connection already available — return immediately
                DequeueAndAccept(payload.caller);
            } else {
                // Block until a connection arrives or we are closed
                // This models the blocking behavior of accept()
                AcceptBlocking(payload.caller);
            }
        }

        on eServerBind do (payload: (caller: machine, addr: tSocketAddress)) {
            // Already bound — IOException
            send payload.caller, eServerBindResp, (status = IO_ERROR,);
            announce eSpec_IOExceptionRaised, (socket = this, operation = "bind");
        }

        on eServerClose do (payload: (caller: machine)) {
            // Javadoc: "Throws: IOException - if an I/O error occurs when closing the socket."
            if ($) {
                send payload.caller, eServerCloseResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "close");
            } else {
                // Unregister from network
                send network, eNetUnlisten, (serverSocket = this, addr = boundAddr);
                send payload.caller, eServerCloseResp, (status = IO_SUCCESS,);
                announce eSpec_SocketClosed, (socket = this,);
                goto Closed;
            }
        }
    }

    state Closed {
        on eServerBind do (payload: (caller: machine, addr: tSocketAddress)) {
            // Javadoc: "Throws: IOException - if the bind operation fails, or if the socket is already bound."
            // Closed socket — Javadoc doesn't explicitly list this as a precondition.
            // Model as nondeterministic.
            if ($) {
                send payload.caller, eServerBindResp, (status = IO_ERROR,);
                announce eSpec_IOExceptionRaised, (socket = this, operation = "bind");
            } else {
                send payload.caller, eServerBindResp, (status = IO_SUCCESS,);
            }
        }

        on eServerAccept do (payload: (caller: machine)) {
            // Javadoc: "Throws: IOException - if an I/O error occurs when waiting for a connection."
            // Closed socket — not explicitly documented as a precondition.
            // Model as nondeterministic.
            if ($) {
                send payload.caller, eServerAcceptResp,
                    (status = IO_ERROR, acceptedSocket = default(machine));
                announce eSpec_IOExceptionRaised, (socket = this, operation = "accept");
            } else {
                send payload.caller, eServerAcceptResp,
                    (status = IO_SUCCESS, acceptedSocket = default(machine));
            }
        }

        on eServerClose do (payload: (caller: machine)) {
            // Closeable contract: close() on already-closed is a no-op. Documented.
            send payload.caller, eServerCloseResp, (status = IO_SUCCESS,);
        }

        // Absorb any late incoming connections (connection was in-flight when we closed)
        ignore eNetIncomingConnection;
    }

    /*** Helper functions ***/

    // Dequeue the first pending connection and return it to the caller
    fun DequeueAndAccept(caller: machine) {
        var accepted: machine;
        accepted = pendingConnections[0];
        pendingConnections -= (0);
        send caller, eServerAcceptResp,
            (status = IO_SUCCESS, acceptedSocket = accepted);
    }

    // Blocking accept: uses receive to wait for either an incoming connection
    // or a close request. In P, receive suspends the machine until a matching
    // event arrives.
    fun AcceptBlocking(caller: machine) {
        receive {
            case eNetIncomingConnection: (payload: (clientSocket: machine, acceptedSocket: machine)) {
                send caller, eServerAcceptResp,
                    (status = IO_SUCCESS, acceptedSocket = payload.acceptedSocket);
            }
            case eServerClose: (payload: (caller: machine)) {
                // Javadoc for ServerSocket.close(): "Any thread currently blocked
                // in accept() will throw a SocketException." This is documented behavior.
                send network, eNetUnlisten, (serverSocket = this, addr = boundAddr);
                send caller, eServerAcceptResp,
                    (status = IO_ERROR, acceptedSocket = default(machine));
                announce eSpec_IOExceptionRaised, (socket = this, operation = "accept");
                send payload.caller, eServerCloseResp, (status = IO_SUCCESS,);
                announce eSpec_SocketClosed, (socket = this,);
                goto Closed;
            }
        }
    }
}
