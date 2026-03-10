/******************************************************************************
 * Network.p
 *
 * Models the network infrastructure that mediates TCP connections.
 *
 * The Network machine serves two roles:
 *   1. Connection broker: maintains a registry of listening ServerSockets and
 *      routes incoming connection requests to the correct server.
 *   2. Channel factory: for each established connection, a NetworkChannel
 *      machine is created to model the bidirectional TCP byte stream.
 *
 * The Network machine is a singleton created by the test driver.
 ******************************************************************************/

/******************************************************************************
 * NetworkChannel: models a single TCP connection (bidirectional byte stream)
 *
 * A NetworkChannel has two endpoints (endpointA and endpointB). Data sent by
 * endpointA is buffered and eventually delivered to endpointB, and vice versa.
 *
 * Models:
 *   - In-order byte delivery (FIFO per direction)
 *   - EOF propagation when one side calls shutdownOutput()
 *   - Connection reset when one side closes abruptly
 ******************************************************************************/
machine NetworkChannel
{
    var endpointA: machine;
    var endpointB: machine;

    // Buffers: data flowing A->B and B->A
    var bufferAtoB: seq[seq[int]];
    var bufferBtoA: seq[seq[int]];

    // EOF flags: set when a side calls shutdownOutput
    var eofAtoB: bool;
    var eofBtoA: bool;

    // Reset flags
    var resetAtoB: bool;
    var resetBtoA: bool;

    start state Init {
        entry (params: (a: machine, b: machine)) {
            endpointA = params.a;
            endpointB = params.b;
            goto Active;
        }
    }

    state Active {
        on eNetSendData do (payload: (sender: machine, dat: seq[int])) {
            if (payload.sender == endpointA) {
                // Data from A to B
                assert !eofAtoB, "Cannot send data after shutdownOutput (A->B)";
                assert !resetAtoB, "Cannot send data on reset channel (A->B)";
                bufferAtoB += (sizeof(bufferAtoB), payload.dat);
                // Nondeterministically deliver immediately or delay
                if ($) {
                    DeliverAtoB();
                }
            } else {
                // Data from B to A
                assert !eofBtoA, "Cannot send data after shutdownOutput (B->A)";
                assert !resetBtoA, "Cannot send data on reset channel (B->A)";
                bufferBtoA += (sizeof(bufferBtoA), payload.dat);
                if ($) {
                    DeliverBtoA();
                }
            }
        }

        on eNetShutdownOutput do (payload: (sender: machine)) {
            if (payload.sender == endpointA) {
                eofAtoB = true;
                // Deliver any remaining buffered data then EOF
                DeliverAllAtoB();
                send endpointB, eNetDeliverEOF;
            } else {
                eofBtoA = true;
                DeliverAllBtoA();
                send endpointA, eNetDeliverEOF;
            }
        }

        on eNetReset do (payload: (sender: machine)) {
            if (payload.sender == endpointA) {
                resetAtoB = true;
                send endpointB, eNetDeliverReset;
            } else {
                resetBtoA = true;
                send endpointA, eNetDeliverReset;
            }
            goto Closed;
        }
    }

    state Closed {
        // Absorb any further messages on a closed channel
        ignore eNetSendData, eNetShutdownOutput, eNetReset;
    }

    /*** Helper functions ***/

    fun DeliverAtoB() {
        var chunk: seq[int];
        if (sizeof(bufferAtoB) > 0) {
            chunk = bufferAtoB[0];
            bufferAtoB -= (0);
            send endpointB, eNetDeliverData, (dat = chunk,);
        }
    }

    fun DeliverBtoA() {
        var chunk: seq[int];
        if (sizeof(bufferBtoA) > 0) {
            chunk = bufferBtoA[0];
            bufferBtoA -= (0);
            send endpointA, eNetDeliverData, (dat = chunk,);
        }
    }

    fun DeliverAllAtoB() {
        while (sizeof(bufferAtoB) > 0) {
            DeliverAtoB();
        }
    }

    fun DeliverAllBtoA() {
        while (sizeof(bufferBtoA) > 0) {
            DeliverBtoA();
        }
    }
}

/******************************************************************************
 * Network: the global network broker
 *
 * Maintains a registry of listening server sockets (addr -> ServerSocket).
 * When a client socket requests a connection, the Network looks up the target
 * address, creates a new accepted Socket machine, creates a NetworkChannel,
 * and wires everything together.
 ******************************************************************************/
machine Network
{
    // Map from (host, port) key string -> listening ServerSocket machine
    var listeners: map[string, machine];

    start state Active {
        on eNetListen do (payload: (serverSocket: machine, addr: tSocketAddress)) {
            var key: string;
            key = AddrToKey(payload.addr);
            if (key in listeners) {
                // Address already in use
                send payload.serverSocket, eNetListenResp, (status = IO_ERROR,);
            } else {
                listeners[key] = payload.serverSocket;
                send payload.serverSocket, eNetListenResp, (status = IO_SUCCESS,);
            }
        }

        on eNetUnlisten do (payload: (serverSocket: machine, addr: tSocketAddress)) {
            var key: string;
            key = AddrToKey(payload.addr);
            if (key in listeners) {
                listeners -= (key);
            }
        }

        on eNetConnect do (payload: (clientSocket: machine, remoteAddr: tSocketAddress)) {
            var key: string;
            var serverSocket: machine;
            var acceptedSocket: machine;
            var channel: machine;

            key = AddrToKey(payload.remoteAddr);
            if (!(key in listeners)) {
                // Connection refused: no server listening
                send payload.clientSocket, eNetConnectResult,
                    (status = CONNECT_REFUSED, networkChannel = default(machine));
            } else {
                serverSocket = listeners[key];

                // Create the server-side (accepted) socket
                // The accepted socket will be initialized as already-connected
                // We pass a placeholder; the ServerSocket.accept() will wire it up
                acceptedSocket = new SocketMachine((
                    role = SOCKET_ACCEPTED,
                    network = this,
                    networkChannel = default(machine)
                ));

                // Create the bidirectional network channel
                channel = new NetworkChannel((
                    a = payload.clientSocket,
                    b = acceptedSocket
                ));

                // Tell the accepted socket about its channel
                send acceptedSocket, eInternalSetChannel, (ch = channel,);

                // Notify the client socket: connection succeeded
                send payload.clientSocket, eNetConnectResult,
                    (status = CONNECT_SUCCESS, networkChannel = channel);

                // Notify the server socket: incoming connection ready to accept
                send serverSocket, eNetIncomingConnection,
                    (clientSocket = payload.clientSocket, acceptedSocket = acceptedSocket);

                // Announce for specification monitors
                announce eSpec_ConnectionEstablished,
                    (clientSocket = payload.clientSocket, serverSocket = serverSocket, acceptedSocket = acceptedSocket);
            }
        }
    }
}

// Helper: convert a socket address to a string key for map lookup
fun AddrToKey(addr: tSocketAddress) : string {
    return format("{0}:{1}", addr.host, addr.port);
}
