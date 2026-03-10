/******************************************************************************
 * TestDriver.p
 *
 * Test driver machines that set up scenarios and exercise the Java Socket API
 * model. Each driver creates the Network, ServerSocket, and Socket machines,
 * then performs a sequence of operations and checks the results.
 *
 * Note on nondeterminism: Many operations (close, shutdownOutput, shutdownInput)
 * can nondeterministically throw IOException per Javadoc. Tests handle this by
 * checking the response and only proceeding when the operation succeeds.
 * When it nondeterministically fails, the test abandons that path (goes to Done).
 * The P model checker explores all paths, so both outcomes are verified.
 ******************************************************************************/

/******************************************************************************
 * TestDriver_BasicClientServer
 *
 * Scenario: A single server binds and listens. A single client connects,
 * sends data, reads a response, and both sides close gracefully.
 ******************************************************************************/
machine TestDriver_BasicClientServer
{
    var network: machine;
    var serverSocket: machine;
    var clientSocket: machine;
    var acceptedSocket: machine;
    var gotAccept: bool;
    var gotConnect: bool;

    start state Setup {
        entry {
            // Create the network
            network = new Network();

            // Create and bind the server socket
            serverSocket = new ServerSocketMachine((net = network,));
            send serverSocket, eServerBind, (caller = this, addr = (host = "localhost", port = 8080));
            receive {
                case eServerBindResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Server bind should succeed";
                }
            }

            // Start accepting in the background by sending accept
            send serverSocket, eServerAccept, (caller = this,);

            // Create client socket and connect
            clientSocket = new SocketMachine((
                role = SOCKET_CLIENT,
                network = network,
                networkChannel = default(machine)
            ));
            send clientSocket, eSocketConnect, (caller = this, remoteAddr = (host = "localhost", port = 8080));

            // We need to receive both the accept response and connect response
            // Order is nondeterministic
            goto WaitForConnection;
        }
    }

    state WaitForConnection {
        on eServerAcceptResp do (resp: (status: tIOResult, acceptedSocket: machine)) {
            assert resp.status == IO_SUCCESS, "Server accept should succeed";
            acceptedSocket = resp.acceptedSocket;
            gotAccept = true;
            if (gotConnect) { goto SendData; }
        }

        on eSocketConnectResp do (resp: (status: tConnectStatus)) {
            assert resp.status == CONNECT_SUCCESS, "Client connect should succeed";
            gotConnect = true;
            if (gotAccept) { goto SendData; }
        }
    }

    state SendData {
        entry {
            var dat: seq[int];
            // Client writes data: [1, 2, 3]
            dat += (0, 1);
            dat += (1, 2);
            dat += (2, 3);
            send clientSocket, eSocketWrite, (caller = this, dat = dat);
            receive {
                case eSocketWriteResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Client write should succeed";
                }
            }

            // Client shuts down output (signals it's done sending)
            // This can nondeterministically fail — only proceed if it succeeds
            send clientSocket, eSocketShutdownOutput, (caller = this,);
            receive {
                case eSocketShutdownOutputResp: (resp: (status: tIOResult)) {
                    if (resp.status != IO_SUCCESS) { goto CloseGracefully; }
                }
            }

            goto ReadOnServer;
        }
    }

    state ReadOnServer {
        entry {
            var respData: seq[int];

            // Server-side (accepted socket) reads the data
            send acceptedSocket, eSocketRead, (caller = this,);
            receive {
                case eSocketReadResp: (resp: (status: tIOResult, dat: seq[int])) {
                    assert resp.status == IO_SUCCESS, "Server read should succeed";
                    assert sizeof(resp.dat) == 3, "Server should read 3 bytes";
                    assert resp.dat[0] == 1 && resp.dat[1] == 2 && resp.dat[2] == 3,
                        "Server should read [1, 2, 3]";
                }
            }

            // Server reads again — should get EOF since client shutdown output
            send acceptedSocket, eSocketRead, (caller = this,);
            receive {
                case eSocketReadResp: (resp: (status: tIOResult, dat: seq[int])) {
                    assert resp.status == IO_EOF, "Server should get EOF after client shutdownOutput";
                }
            }

            // Server writes a response: [10, 20]
            respData += (0, 10);
            respData += (1, 20);
            send acceptedSocket, eSocketWrite, (caller = this, dat = respData);
            receive {
                case eSocketWriteResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Server write should succeed";
                }
            }

            goto CloseGracefully;
        }
    }

    state CloseGracefully {
        entry {
            // Close both sides — close() can nondeterministically throw IOException
            send acceptedSocket, eSocketClose, (caller = this,);
            receive {
                case eSocketCloseResp: (resp: (status: tIOResult)) {
                    // close() may nondeterministically fail; don't assert success
                }
            }

            send clientSocket, eSocketClose, (caller = this,);
            receive {
                case eSocketCloseResp: (resp: (status: tIOResult)) {
                    // close() may nondeterministically fail
                }
            }

            send serverSocket, eServerClose, (caller = this,);
            receive {
                case eServerCloseResp: (resp: (status: tIOResult)) {
                    // close() may nondeterministically fail
                }
            }

            goto Done;
        }
    }

    state Done {}
}

/******************************************************************************
 * TestDriver_ConnectionRefused
 *
 * Scenario: Client tries to connect to a port where no server is listening.
 * The connection should be refused.
 ******************************************************************************/
machine TestDriver_ConnectionRefused
{
    var network: machine;
    var clientSocket: machine;

    start state Setup {
        entry {
            network = new Network();
            clientSocket = new SocketMachine((
                role = SOCKET_CLIENT,
                network = network,
                networkChannel = default(machine)
            ));

            // Connect to a port where nobody is listening
            send clientSocket, eSocketConnect,
                (caller = this, remoteAddr = (host = "localhost", port = 9999));
            receive {
                case eSocketConnectResp: (resp: (status: tConnectStatus)) {
                    assert resp.status == CONNECT_REFUSED,
                        "Connection should be refused when no server is listening";
                }
            }

            goto Done;
        }
    }

    state Done {}
}

/******************************************************************************
 * TestDriver_WriteAfterClose
 *
 * Scenario: Client connects, closes the socket, then tries to write.
 * The write should fail with IOException.
 *
 * Javadoc basis: close() doc says "Once a socket has been closed, it is not
 * available for further networking use." getInputStream() doc says throws
 * IOException if "the socket is closed."
 ******************************************************************************/
machine TestDriver_WriteAfterClose
{
    var network: machine;
    var serverSocket: machine;
    var clientSocket: machine;

    start state Setup {
        entry {
            network = new Network();
            serverSocket = new ServerSocketMachine((net = network,));
            send serverSocket, eServerBind, (caller = this, addr = (host = "localhost", port = 8080));
            receive {
                case eServerBindResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Bind should succeed";
                }
            }

            send serverSocket, eServerAccept, (caller = this,);

            clientSocket = new SocketMachine((
                role = SOCKET_CLIENT,
                network = network,
                networkChannel = default(machine)
            ));
            send clientSocket, eSocketConnect,
                (caller = this, remoteAddr = (host = "localhost", port = 8080));

            goto WaitConnect;
        }
    }

    state WaitConnect {
        on eServerAcceptResp do (resp: (status: tIOResult, acceptedSocket: machine)) {
            // Don't need the accepted socket for this test
        }

        on eSocketConnectResp do (resp: (status: tConnectStatus)) {
            assert resp.status == CONNECT_SUCCESS, "Connect should succeed";
            goto CloseAndWrite;
        }
    }

    state CloseAndWrite {
        ignore eServerAcceptResp;

        entry {
            var dat: seq[int];

            // Close the client socket — can nondeterministically fail
            send clientSocket, eSocketClose, (caller = this,);
            receive {
                case eSocketCloseResp: (resp: (status: tIOResult)) {
                    if (resp.status != IO_SUCCESS) { goto Done; }
                }
            }

            // Try to write after close — should fail (documented behavior)
            dat += (0, 42);
            send clientSocket, eSocketWrite, (caller = this, dat = dat);
            receive {
                case eSocketWriteResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_ERROR,
                        "Write after close should return IO_ERROR";
                }
            }

            goto Done;
        }
    }

    state Done {
        ignore eServerAcceptResp;
    }
}

/******************************************************************************
 * TestDriver_WriteAfterShutdownOutput
 *
 * Scenario: Client connects, calls shutdownOutput(), then tries to write.
 * The write should fail with IOException.
 *
 * Javadoc basis: shutdownOutput() doc says "If you write to a socket output
 * stream after invoking shutdownOutput() on the socket, the stream will throw
 * an IOException."
 ******************************************************************************/
machine TestDriver_WriteAfterShutdownOutput
{
    var network: machine;
    var serverSocket: machine;
    var clientSocket: machine;

    start state Setup {
        entry {
            network = new Network();
            serverSocket = new ServerSocketMachine((net = network,));
            send serverSocket, eServerBind, (caller = this, addr = (host = "localhost", port = 8080));
            receive {
                case eServerBindResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Bind should succeed";
                }
            }

            send serverSocket, eServerAccept, (caller = this,);

            clientSocket = new SocketMachine((
                role = SOCKET_CLIENT,
                network = network,
                networkChannel = default(machine)
            ));
            send clientSocket, eSocketConnect,
                (caller = this, remoteAddr = (host = "localhost", port = 8080));

            goto WaitConnect;
        }
    }

    state WaitConnect {
        on eServerAcceptResp do (resp: (status: tIOResult, acceptedSocket: machine)) {
            // Accepted socket not needed for this test
        }

        on eSocketConnectResp do (resp: (status: tConnectStatus)) {
            assert resp.status == CONNECT_SUCCESS, "Connect should succeed";
            goto ShutdownAndWrite;
        }
    }

    state ShutdownAndWrite {
        ignore eServerAcceptResp;

        entry {
            var dat: seq[int];

            // Shutdown output — can nondeterministically fail
            send clientSocket, eSocketShutdownOutput, (caller = this,);
            receive {
                case eSocketShutdownOutputResp: (resp: (status: tIOResult)) {
                    if (resp.status != IO_SUCCESS) { goto Done; }
                }
            }

            // Try to write after shutdownOutput — should fail (documented behavior)
            dat += (0, 42);
            send clientSocket, eSocketWrite, (caller = this, dat = dat);
            receive {
                case eSocketWriteResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_ERROR,
                        "Write after shutdownOutput should return IO_ERROR";
                }
            }

            goto Done;
        }
    }

    state Done {
        ignore eServerAcceptResp;
    }
}

/******************************************************************************
 * TestDriver_AcceptOnClosedServer
 *
 * Scenario: Server binds, then closes, then tries to accept.
 * With the nondeterministic model, accept on a closed server may or may not
 * fail — the Javadoc doesn't explicitly document this precondition.
 ******************************************************************************/
machine TestDriver_AcceptOnClosedServer
{
    var network: machine;
    var serverSocket: machine;

    start state Setup {
        entry {
            network = new Network();
            serverSocket = new ServerSocketMachine((net = network,));

            send serverSocket, eServerBind, (caller = this, addr = (host = "localhost", port = 8080));
            receive {
                case eServerBindResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Bind should succeed";
                }
            }

            // Close the server socket — can nondeterministically fail
            send serverSocket, eServerClose, (caller = this,);
            receive {
                case eServerCloseResp: (resp: (status: tIOResult)) {
                    if (resp.status != IO_SUCCESS) { goto Done; }
                }
            }

            // Try to accept after close — behavior is nondeterministic
            // (Javadoc doesn't explicitly document accept-on-closed as a precondition)
            send serverSocket, eServerAccept, (caller = this,);
            receive {
                case eServerAcceptResp: (resp: (status: tIOResult, acceptedSocket: machine)) {
                    // Either outcome is possible in our model
                }
            }

            goto Done;
        }
    }

    state Done {}
}

/******************************************************************************
 * TestDriver_ReadAfterPeerShutdown
 *
 * Scenario: Client connects, peer (accepted socket) calls shutdownOutput().
 * Client should read EOF.
 *
 * Javadoc basis: shutdownOutput() doc says "any previously written data will
 * be sent followed by TCP's normal connection termination sequence."
 * shutdownInput() doc says "read methods will return -1 (end of stream)."
 * The peer receiving the termination sequence will see EOF on read.
 ******************************************************************************/
machine TestDriver_ReadAfterPeerShutdown
{
    var network: machine;
    var serverSocket: machine;
    var clientSocket: machine;
    var acceptedSocket: machine;
    var gotAccept: bool;
    var gotConnect: bool;

    start state Setup {
        entry {
            network = new Network();
            serverSocket = new ServerSocketMachine((net = network,));
            send serverSocket, eServerBind, (caller = this, addr = (host = "localhost", port = 8080));
            receive {
                case eServerBindResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Bind should succeed";
                }
            }

            send serverSocket, eServerAccept, (caller = this,);

            clientSocket = new SocketMachine((
                role = SOCKET_CLIENT,
                network = network,
                networkChannel = default(machine)
            ));
            send clientSocket, eSocketConnect,
                (caller = this, remoteAddr = (host = "localhost", port = 8080));

            goto WaitConnect;
        }
    }

    state WaitConnect {
        on eServerAcceptResp do (resp: (status: tIOResult, acceptedSocket: machine)) {
            assert resp.status == IO_SUCCESS, "Accept should succeed";
            acceptedSocket = resp.acceptedSocket;
            gotAccept = true;
            if (gotConnect) { goto PeerShutdownAndRead; }
        }

        on eSocketConnectResp do (resp: (status: tConnectStatus)) {
            assert resp.status == CONNECT_SUCCESS, "Connect should succeed";
            gotConnect = true;
            if (gotAccept) { goto PeerShutdownAndRead; }
        }
    }

    state PeerShutdownAndRead {
        entry {
            // Accepted socket shuts down output — can nondeterministically fail
            send acceptedSocket, eSocketShutdownOutput, (caller = this,);
            receive {
                case eSocketShutdownOutputResp: (resp: (status: tIOResult)) {
                    if (resp.status != IO_SUCCESS) { goto Done; }
                }
            }

            // Client reads — should get EOF (documented: TCP termination sequence)
            send clientSocket, eSocketRead, (caller = this,);
            receive {
                case eSocketReadResp: (resp: (status: tIOResult, dat: seq[int])) {
                    assert resp.status == IO_EOF,
                        "Read should return EOF after peer shutdownOutput";
                }
            }

            goto Done;
        }
    }

    state Done {}
}

/******************************************************************************
 * TestDriver_HalfClose
 *
 * Scenario: Tests the full TCP half-close pattern:
 *   1. Client connects to server
 *   2. Client writes [1, 2, 3], then calls shutdownOutput() (half-close)
 *   3. Server reads [1, 2, 3], then reads EOF
 *   4. Server writes [10, 20] back (reverse direction still open)
 *   5. Server calls shutdownOutput() (now both directions have EOF)
 *   6. Client reads [10, 20], then reads EOF
 *   7. Both sides close gracefully
 *
 * This verifies that after one side half-closes, the other side can still
 * send data in the reverse direction, and the half-closed side can still
 * read that data.
 ******************************************************************************/
machine TestDriver_HalfClose
{
    var network: machine;
    var serverSocket: machine;
    var clientSocket: machine;
    var acceptedSocket: machine;
    var gotAccept: bool;
    var gotConnect: bool;

    start state Setup {
        entry {
            network = new Network();
            serverSocket = new ServerSocketMachine((net = network,));
            send serverSocket, eServerBind, (caller = this, addr = (host = "localhost", port = 8080));
            receive {
                case eServerBindResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Bind should succeed";
                }
            }

            send serverSocket, eServerAccept, (caller = this,);

            clientSocket = new SocketMachine((
                role = SOCKET_CLIENT,
                network = network,
                networkChannel = default(machine)
            ));
            send clientSocket, eSocketConnect,
                (caller = this, remoteAddr = (host = "localhost", port = 8080));

            goto WaitConnect;
        }
    }

    state WaitConnect {
        on eServerAcceptResp do (resp: (status: tIOResult, acceptedSocket: machine)) {
            assert resp.status == IO_SUCCESS, "Accept should succeed";
            acceptedSocket = resp.acceptedSocket;
            gotAccept = true;
            if (gotConnect) { goto ClientSendsAndHalfCloses; }
        }

        on eSocketConnectResp do (resp: (status: tConnectStatus)) {
            assert resp.status == CONNECT_SUCCESS, "Connect should succeed";
            gotConnect = true;
            if (gotAccept) { goto ClientSendsAndHalfCloses; }
        }
    }

    state ClientSendsAndHalfCloses {
        entry {
            var dat: seq[int];

            // Step 2: Client writes [1, 2, 3]
            dat += (0, 1);
            dat += (1, 2);
            dat += (2, 3);
            send clientSocket, eSocketWrite, (caller = this, dat = dat);
            receive {
                case eSocketWriteResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS, "Client write should succeed";
                }
            }

            // Client half-closes: shuts down output, but input stays open
            // Can nondeterministically fail
            send clientSocket, eSocketShutdownOutput, (caller = this,);
            receive {
                case eSocketShutdownOutputResp: (resp: (status: tIOResult)) {
                    if (resp.status != IO_SUCCESS) { goto GracefulClose; }
                }
            }

            // Verify client can no longer write (documented: "stream will throw an IOException")
            send clientSocket, eSocketWrite, (caller = this, dat = dat);
            receive {
                case eSocketWriteResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_ERROR,
                        "Client write after shutdownOutput should fail";
                }
            }

            goto ServerReadsAndResponds;
        }
    }

    state ServerReadsAndResponds {
        entry {
            var respData: seq[int];

            // Step 3: Server reads client's data
            send acceptedSocket, eSocketRead, (caller = this,);
            receive {
                case eSocketReadResp: (resp: (status: tIOResult, dat: seq[int])) {
                    assert resp.status == IO_SUCCESS, "Server read should succeed";
                    assert sizeof(resp.dat) == 3, "Server should read 3 bytes";
                    assert resp.dat[0] == 1 && resp.dat[1] == 2 && resp.dat[2] == 3,
                        "Server should read [1, 2, 3]";
                }
            }

            // Server reads EOF (client half-closed)
            send acceptedSocket, eSocketRead, (caller = this,);
            receive {
                case eSocketReadResp: (resp: (status: tIOResult, dat: seq[int])) {
                    assert resp.status == IO_EOF,
                        "Server should get EOF after client shutdownOutput";
                }
            }

            // Step 4: Server writes response [10, 20] back (reverse direction still open)
            respData += (0, 10);
            respData += (1, 20);
            send acceptedSocket, eSocketWrite, (caller = this, dat = respData);
            receive {
                case eSocketWriteResp: (resp: (status: tIOResult)) {
                    assert resp.status == IO_SUCCESS,
                        "Server write should succeed (reverse direction still open after client half-close)";
                }
            }

            // Step 5: Server also half-closes — can nondeterministically fail
            send acceptedSocket, eSocketShutdownOutput, (caller = this,);
            receive {
                case eSocketShutdownOutputResp: (resp: (status: tIOResult)) {
                    if (resp.status != IO_SUCCESS) { goto GracefulClose; }
                }
            }

            goto ClientReadsResponse;
        }
    }

    state ClientReadsResponse {
        entry {
            // Step 6: Client reads server's response (input still open despite output shutdown)
            send clientSocket, eSocketRead, (caller = this,);
            receive {
                case eSocketReadResp: (resp: (status: tIOResult, dat: seq[int])) {
                    assert resp.status == IO_SUCCESS,
                        "Client should still be able to read after shutdownOutput (half-close)";
                    assert sizeof(resp.dat) == 2, "Client should read 2 bytes";
                    assert resp.dat[0] == 10 && resp.dat[1] == 20,
                        "Client should read [10, 20]";
                }
            }

            // Client reads EOF (server half-closed)
            send clientSocket, eSocketRead, (caller = this,);
            receive {
                case eSocketReadResp: (resp: (status: tIOResult, dat: seq[int])) {
                    assert resp.status == IO_EOF,
                        "Client should get EOF after server shutdownOutput";
                }
            }

            goto GracefulClose;
        }
    }

    state GracefulClose {
        entry {
            // Step 7: Both sides close gracefully
            // close() can nondeterministically throw IOException — don't assert success
            send acceptedSocket, eSocketClose, (caller = this,);
            receive {
                case eSocketCloseResp: (resp: (status: tIOResult)) {
                    // May nondeterministically fail
                }
            }

            send clientSocket, eSocketClose, (caller = this,);
            receive {
                case eSocketCloseResp: (resp: (status: tIOResult)) {
                    // May nondeterministically fail
                }
            }

            send serverSocket, eServerClose, (caller = this,);
            receive {
                case eServerCloseResp: (resp: (status: tIOResult)) {
                    // May nondeterministically fail
                }
            }

            goto Done;
        }
    }

    state Done {}
}
