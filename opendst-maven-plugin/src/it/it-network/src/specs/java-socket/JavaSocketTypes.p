/******************************************************************************
 * JavaSocketTypes.p
 *
 * Specification observation events for the Java Socket API contract.
 *
 * These events are emitted by runtime socket decorators (TracingSocket,
 * TracingServerSocket) and consumed by the spec monitors in Specifications.p.
 * The monitors validate that instrumented code correctly follows the
 * Java Socket API contract.
 ******************************************************************************/

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
// Announced when shutdownInput() succeeds on a socket
event eSpec_ShutdownInputCompleted: (socket: machine);
// Announced when available() is queried on a socket
event eSpec_AvailableQueried: (socket: machine, reportedCount: int);
// Announced when a client socket connects (client-side)
event eSpec_SocketConnected: (socket: machine);
// Announced when a server socket accepts a connection (server-side)
event eSpec_SocketAccepted: (socket: machine);
