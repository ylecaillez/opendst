/*
 * Copyright 2025-2026 Ping Identity Corporation
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
package com.pingidentity.opendst;

import static com.pingidentity.opendst.Node.currentNodeOrNull;
import static com.pingidentity.opendst.Node.currentNodeOrThrow;
import static java.util.Collections.unmodifiableCollection;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.SocketImplFactory;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Functional module for network simulation and instrumentation.
 * <p>
 * This class manages host name to IP address mapping (DNS), routing,
 * and network partitions within the simulation.
 */
public final class Network {
    private static final int MAX_NODES = 100;
    private static final int MAX_ADDRESSES = 256;

    private final Simulator simulator;
    private final Map<InetAddress, String> addressToName = new HashMap<>();
    private final Map<String, Node> nameToNode = new HashMap<>();
    private final Map<String, Set<String>> partitions = new HashMap<>();
    private long baseLatencyMs = 0;

    Network(Simulator simulator) {
        this.simulator = requireNonNull(simulator);
    }

    List<InetAddress> allAddresses() {
        return List.copyOf(addressToName.keySet());
    }

    // --- DNS and Registry ---

    void registerDns(String hostName, Node host) {
        var lowerCaseHostName = hostName.toLowerCase(ROOT);
        if (nameToNode.size() >= MAX_NODES && !nameToNode.containsKey(lowerCaseHostName)) {
            simulator.exitSimulation(
                    Simulator.ExitReason.INTERNAL_ERROR,
                    new Simulator.SimulationError("Maximum number of nodes reached"));
        }
        if (nameToNode.putIfAbsent(lowerCaseHostName, host) == null) {
            host.inetAddresses().forEach(address -> {
                if (!address.isLoopbackAddress()) {
                    if (addressToName.size() >= MAX_ADDRESSES && !addressToName.containsKey(address)) {
                        simulator.exitSimulation(
                                Simulator.ExitReason.INTERNAL_ERROR,
                                new Simulator.SimulationError("Maximum number of addresses reached"));
                    }
                    addressToName.put(address, lowerCaseHostName);
                }
            });
        }
    }

    void unregisterDns(String hostName) {
        var hostname = hostName.toLowerCase(ROOT);
        var host = nameToNode.remove(hostname);
        if (host != null) {
            host.inetAddresses().forEach(address -> addressToName.remove(address, hostname));
        }
    }

    public String lookupByAddress(InetAddress addr) throws UnknownHostException {
        var hostName = addressToName.get(addr);
        if (hostName == null) {
            throw new UnknownHostException(addr.getHostAddress());
        }
        return hostName;
    }

    public Stream<InetAddress> lookupByName(String name) throws UnknownHostException {
        var resolvedHost = nameToNode.get(name.toLowerCase(ROOT));
        if (resolvedHost == null) {
            throw new UnknownHostException(name);
        }
        return resolvedHost.inetAddresses();
    }

    private Node lookupHostByAddress(InetAddress addr) throws UnknownHostException {
        var host = nameToNode.get(lookupByAddress(addr));
        if (host == null) {
            throw new UnknownHostException(addr.getHostAddress());
        }
        return host;
    }

    // --- Routing and Partitions ---

    @SuppressWarnings({"deprecation", "removal"})
    SocketImpl route(InetAddress from, InetAddress addr, int port) throws UnknownHostException, NoRouteToHostException {
        if (from.isLoopbackAddress() && !addr.isLoopbackAddress()) {
            throw new NoRouteToHostException();
        }

        var fromHost = addressToName.get(from);
        var toHost = addressToName.get(addr);
        if (fromHost != null && toHost != null && !canReach(fromHost, toHost)) {
            throw new NoRouteToHostException("Network partition: " + fromHost + " -> " + toHost);
        }

        return lookupHostByAddress(addr).route(from, addr, port);
    }

    public boolean canReach(String from, String to) {
        if (from.equals(to)) return true;
        var unreachable = partitions.get(from);
        return unreachable == null || !unreachable.contains(to);
    }

    public void partition(Set<String> sideA, Set<String> sideB) {
        sideA.forEach(a -> partitions.computeIfAbsent(a, _ -> new HashSet<>()).addAll(sideB));
        sideB.forEach(b -> partitions.computeIfAbsent(b, _ -> new HashSet<>()).addAll(sideA));
    }

    public void heal() {
        partitions.clear();
    }

    public void heal(Set<String> sideA, Set<String> sideB) {
        sideA.forEach(a -> {
            var unreachable = partitions.get(a);
            if (unreachable != null) {
                unreachable.removeAll(sideB);
            }
        });
        sideB.forEach(b -> {
            var unreachable = partitions.get(b);
            if (unreachable != null) {
                unreachable.removeAll(sideA);
            }
        });
    }

    // --- Latency ---

    public void setBaseLatency(long latencyMs) {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must be >= 0");
        }
        this.baseLatencyMs = latencyMs;
    }

    public long currentLatencyMs() {
        if (baseLatencyMs == 0) return 0;
        return baseLatencyMs + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, Math.max(1, baseLatencyMs / 10));
    }

    Map<String, Node> nodes() {
        return nameToNode;
    }

    // --- Static Interception Hooks ---

    /**
     * DNS resolver provider that uses the simulation's DNS registry.
     */
    public static final class ResolverProvider extends InetAddressResolverProvider {
        @Override
        public InetAddressResolver get(Configuration configuration) {
            var builtinResolver = configuration.builtinResolver();
            return new InetAddressResolver() {
                @Override
                public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                        throws UnknownHostException {
                    var node = currentNodeOrNull();
                    return (node != null)
                            ? node.context.network().lookupByName(host)
                            : builtinResolver.lookupByName(host, lookupPolicy);
                }

                @Override
                public String lookupByAddress(byte[] addr) throws UnknownHostException {
                    var node = currentNodeOrNull();
                    return (node != null)
                            ? node.context.network().lookupByAddress(InetAddress.getByAddress(addr))
                            : builtinResolver.lookupByAddress(addr);
                }
            };
        }

        @Override
        public String name() {
            return "SimulatedDNS";
        }
    }

    /**
     * A socket implementation factory that produces Node-specific socket implementations.
     */
    @SuppressWarnings({"deprecation", "removal"})
    public static final class NodeSocketImplFactory implements SocketImplFactory {
        @Override
        public SocketImpl createSocketImpl() {
            var node = currentNodeOrNull();
            if (node != null) {
                return node.newSocketImpl(false);
            }
            return null;
        }
    }

    private static boolean factoryInstalled = false;

    public static Socket newSocket(String host, int port) throws IOException {
        return currentNodeOrThrow().socketFactory().createSocket(host, port);
    }

    public static Socket newSocket(Proxy proxy) {
        return new Socket(proxy);
    }

    public static Socket newSocket() throws IOException {
        return currentNodeOrThrow().socketFactory().createSocket();
    }

    public static Socket newSocket(InetAddress host, int port) throws IOException {
        return currentNodeOrThrow().socketFactory().createSocket(host, port);
    }

    public static Socket newSocket(InetAddress host, int port, boolean stream) throws IOException {
        if (!stream) {
            throw new UnsupportedOperationException("Datagram sockets are not supported");
        }
        return currentNodeOrThrow().socketFactory().createSocket(host, port);
    }

    public static Socket newSocket(String host, int port, boolean stream) throws IOException {
        if (!stream) {
            throw new UnsupportedOperationException("Datagram sockets are not supported");
        }
        return currentNodeOrThrow().socketFactory().createSocket(host, port);
    }

    public static Socket newSocket(InetAddress address, int port, InetAddress localAddr, int localPort)
            throws IOException {
        return currentNodeOrThrow().socketFactory().createSocket(address, port, localAddr, localPort);
    }

    public static Socket newSocket(String host, int port, InetAddress localAddr, int localPort) throws IOException {
        return currentNodeOrThrow().socketFactory().createSocket(host, port, localAddr, localPort);
    }

    public static ServerSocket newServerSocket(int port) throws IOException {
        return currentNodeOrThrow().serverSocketFactory().createServerSocket(port);
    }

    public static ServerSocket newServerSocket() throws IOException {
        return currentNodeOrThrow().serverSocketFactory().createServerSocket();
    }

    public static ServerSocket newServerSocket(int port, int backlog) throws IOException {
        return currentNodeOrThrow().serverSocketFactory().createServerSocket(port, backlog);
    }

    public static ServerSocket newServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException {
        return currentNodeOrThrow().serverSocketFactory().createServerSocket(port, backlog, ifAddress);
    }

    @Intercepts("java.net.InetAddress#getLocalHost()")
    public static final class InetAddressGetLocalHost {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static InetAddress onEnter() {
            var node = currentNodeOrNull();
            return node != null ? node.getLocalHost() : null;
        }

        @Advice.OnMethodExit
        public static void onExit(
                @Advice.Return(readOnly = false) InetAddress returned, @Advice.Enter InetAddress simulatedAddress) {
            if (simulatedAddress != null) {
                returned = simulatedAddress;
            }
        }
    }
/*
    @Intercepts("javax.net.SocketFactory#getDefault()")
    public static class GetDefaultSocketFactoryAdvice {
        @Advice.OnMethodExit
        public static void exit(@Advice.Return(readOnly = false) SocketFactory factory) {
            var node = currentNodeOrNull();
            if (node != null) {
                factory = node.socketFactory();
            }
        }
    }

    @Intercepts("javax.net.ServerSocketFactory#getDefault()")
    public static class GetDefaultServerSocketFactoryAdvice {
        @Advice.OnMethodExit
        public static void exit(@Advice.Return(readOnly = false) ServerSocketFactory factory) {
            var node = currentNodeOrNull();
            if (node != null) {
                factory = node.serverSocketFactory();
            }
        }
    }
*/
    @SuppressWarnings({"deprecation", "removal"})
    static AgentBuilder instrument(AgentBuilder agent) {
        if (!factoryInstalled) {
            try {
                Socket.setSocketImplFactory(() -> currentNodeOrThrow().newSocketImpl(false));
                ServerSocket.setSocketFactory(() -> currentNodeOrThrow().newSocketImpl(true));
                factoryInstalled = true;
            } catch (Throwable e) {
                throw new Simulator.SimulationError("Failed to install socket factories — "
                        + "network isolation cannot be guaranteed", e);
            }
        }

        return agent.type(named("java.net.InetAddress"))
                .transform((builder, _, _, _, _) -> builder.visit(to(InetAddressGetLocalHost.class)
                        .on(named("getLocalHost")
                                .and(isPublic())
                                .and(isStatic())
                                .and(returns(InetAddress.class))
                                .and(takesNoArguments()))))
                .asTerminalTransformation();
                // .type(is(SocketFactory.class))
               /* .transform((builder, _, _, _, _) -> builder.visit(
                        Advice.to(GetDefaultSocketFactoryAdvice.class).on(named("getDefault"))))
                .type(is(ServerSocketFactory.class))
                .transform((builder, _, _, _, _) -> builder.visit(
                        Advice.to(GetDefaultServerSocketFactoryAdvice.class).on(named("getDefault")))); */
    }
}
