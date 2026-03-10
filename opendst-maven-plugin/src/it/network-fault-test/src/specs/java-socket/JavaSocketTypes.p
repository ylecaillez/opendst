/******************************************************************************
 * JavaSocketTypes.p
 *
 * Type and event declarations for modeling the Java Socket API.
 *
 * This spec models the interaction between:
 *   - ServerSocket: binds to a port, listens, and accepts connections
 *   - Socket (client-side): connects to a server
 *   - Socket (accepted-side): returned by ServerSocket.accept()
 *   - Network: an intermediary that models the TCP byte stream between two
 *     connected sockets, including asynchronous delivery and connection reset.
 *
 * Key Java Socket API behaviors modeled:
 *   - IOException on write()/read() after close()
 *   - IOException on write() after shutdownOutput()
 *   - EOF (-1) on read() after peer shutdownOutput() or peer close()
 *   - IOException on getInputStream()/getOutputStream() when socket is closed
 *     or not connected or input/output has been shutdown
 *   - ServerSocket.accept() blocks until a connection arrives
 *   - ServerSocket.accept() throws IOException if ServerSocket is closed
 *   - Connection refused if no ServerSocket is listening on the target port
 ******************************************************************************/

/***********************************************
 * Address and port types
 ***********************************************/
type tSocketAddress = (host: string, port: int);

/***********************************************
 * Enums for status/result
 ***********************************************/
enum tConnectStatus {
    CONNECT_SUCCESS,
    CONNECT_REFUSED,
    CONNECT_IOERROR
}

enum tIOResult {
    IO_SUCCESS,
    IO_EOF,
    IO_ERROR
}

/***********************************************
 * Events: User actions on ServerSocket
 *
 * These model the Java API methods that a user
 * calls on a ServerSocket instance.
 ***********************************************/

// ServerSocket.bind(addr) - bind the server socket to an address
event eServerBind: (caller: machine, addr: tSocketAddress);
// Response to bind
event eServerBindResp: (status: tIOResult);

// ServerSocket.accept() - block until a connection arrives
event eServerAccept: (caller: machine);
// Response to accept: returns the accepted Socket machine reference
event eServerAcceptResp: (status: tIOResult, acceptedSocket: machine);

// ServerSocket.close()
event eServerClose: (caller: machine);
event eServerCloseResp: (status: tIOResult);

/***********************************************
 * Events: User actions on Socket (client-side)
 *
 * These model the Java API methods that a user
 * calls on a Socket instance.
 ***********************************************/

// new Socket() + socket.connect(addr) combined
event eSocketConnect: (caller: machine, remoteAddr: tSocketAddress);
event eSocketConnectResp: (status: tConnectStatus);

// socket.getOutputStream().write(data)
event eSocketWrite: (caller: machine, dat: seq[int]);
event eSocketWriteResp: (status: tIOResult);

// socket.getInputStream().read()
event eSocketRead: (caller: machine);
event eSocketReadResp: (status: tIOResult, dat: seq[int]);

// socket.shutdownOutput() - sends TCP FIN for the output side
event eSocketShutdownOutput: (caller: machine);
event eSocketShutdownOutputResp: (status: tIOResult);

// socket.shutdownInput() - locally marks input as shutdown
event eSocketShutdownInput: (caller: machine);
event eSocketShutdownInputResp: (status: tIOResult);

// socket.close()
event eSocketClose: (caller: machine);
event eSocketCloseResp: (status: tIOResult);

/***********************************************
 * Events: Network-level (internal)
 *
 * These model the asynchronous TCP byte stream
 * between two connected socket endpoints.
 ***********************************************/

// A connection request from a client socket to the network
event eNetConnect: (clientSocket: machine, remoteAddr: tSocketAddress);
// Network tells the server socket about an incoming connection
event eNetIncomingConnection: (clientSocket: machine, acceptedSocket: machine);
// Network confirms connection to the client
event eNetConnectResult: (status: tConnectStatus, networkChannel: machine);

// Data sent from one endpoint into the network channel
event eNetSendData: (sender: machine, dat: seq[int]);
// Data delivered from the network to the receiving endpoint
event eNetDeliverData: (dat: seq[int]);

// Shutdown output notification (TCP FIN) propagated through network
event eNetShutdownOutput: (sender: machine);
// Delivered EOF to the peer
event eNetDeliverEOF;

// Connection reset (e.g. when one side closes abruptly)
event eNetReset: (sender: machine);
event eNetDeliverReset;

// Register a server socket as listening on a port
event eNetListen: (serverSocket: machine, addr: tSocketAddress);
event eNetListenResp: (status: tIOResult);
// Unregister (server socket closed)
event eNetUnlisten: (serverSocket: machine, addr: tSocketAddress);

/***********************************************
 * Events: Specification / observation events
 ***********************************************/
// Announced when a connection is fully established between two endpoints
event eSpec_ConnectionEstablished: (clientSocket: machine, serverSocket: machine, acceptedSocket: machine);
// Announced when data is written by a socket
event eSpec_DataWritten: (socket: machine, dat: seq[int]);
// Announced when data is read by a socket
event eSpec_DataRead: (socket: machine, dat: seq[int]);
// Announced when a socket is closed
event eSpec_SocketClosed: (socket: machine);
// Announced when an IOException is raised (write/read on closed socket, etc.)
event eSpec_IOExceptionRaised: (socket: machine, operation: string);
// Announced when shutdownOutput() succeeds on a connected socket
event eSpec_ShutdownOutputCompleted: (socket: machine);
// Announced when a read returns EOF (IO_EOF)
event eSpec_EOFRead: (socket: machine);
// Announced when the connection is reset (peer closed abruptly)
event eSpec_ConnectionReset: (socket: machine);

/***********************************************
 * Enums for socket roles
 ***********************************************/
enum tSocketRole {
    SOCKET_CLIENT,
    SOCKET_ACCEPTED
}

/***********************************************
 * Internal events (used between machines)
 ***********************************************/
// Used by Network to tell an accepted Socket about its channel
event eInternalSetChannel: (ch: machine);
