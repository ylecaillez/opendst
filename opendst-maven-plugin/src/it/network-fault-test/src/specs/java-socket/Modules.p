/******************************************************************************
 * Modules.p
 *
 * Module declarations and test case definitions for the Java Socket API spec.
 *
 * Follows P conventions:
 *   - Modules group related machines
 *   - Test cases specify a main driver and the modules + specs to check
 ******************************************************************************/

/***********************************************
 * Core module: all implementation machines
 ***********************************************/
module NetworkModule = { Network, NetworkChannel };
module SocketModule = { SocketMachine };
module ServerSocketModule = { ServerSocketMachine };

// Combined implementation module
module JavaSocketImpl = (union NetworkModule, SocketModule, ServerSocketModule);

/***********************************************
 * Test cases
 *
 * Each test case specifies:
 *   - [main=DriverMachine]: the entry point
 *   - The implementation modules (union of all machines needed)
 *   - assert SpecName, ... in ...: which specs to check
 ***********************************************/

// Test 1: Basic client-server interaction with data exchange
test tcBasicClientServer [main = TestDriver_BasicClientServer]:
    assert DataIntegrity, NoWriteAfterClose, NoPhantomData, IOExceptionOnClosedSocket, DeliveryLiveness, EOFLiveness in
    (union JavaSocketImpl, { TestDriver_BasicClientServer });

// Test 2: Connection refused (no server listening)
test tcConnectionRefused [main = TestDriver_ConnectionRefused]:
    (union JavaSocketImpl, { TestDriver_ConnectionRefused });

// Test 3: Write after close
test tcWriteAfterClose [main = TestDriver_WriteAfterClose]:
    assert NoWriteAfterClose, IOExceptionOnClosedSocket in
    (union JavaSocketImpl, { TestDriver_WriteAfterClose });

// Test 4: Write after shutdownOutput
test tcWriteAfterShutdownOutput [main = TestDriver_WriteAfterShutdownOutput]:
    (union JavaSocketImpl, { TestDriver_WriteAfterShutdownOutput });

// Test 5: Accept on closed server
test tcAcceptOnClosedServer [main = TestDriver_AcceptOnClosedServer]:
    (union JavaSocketImpl, { TestDriver_AcceptOnClosedServer });

// Test 6: Read after peer shutdown
test tcReadAfterPeerShutdown [main = TestDriver_ReadAfterPeerShutdown]:
    assert DataIntegrity, NoPhantomData, EOFLiveness in
    (union JavaSocketImpl, { TestDriver_ReadAfterPeerShutdown });

// Test 7: Full half-close pattern (bidirectional data with half-close)
test tcHalfClose [main = TestDriver_HalfClose]:
    assert DataIntegrity, NoWriteAfterClose, NoPhantomData, IOExceptionOnClosedSocket, DeliveryLiveness, EOFLiveness in
    (union JavaSocketImpl, { TestDriver_HalfClose });
