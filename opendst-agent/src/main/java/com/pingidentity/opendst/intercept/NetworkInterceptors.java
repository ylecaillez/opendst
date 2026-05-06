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
package com.pingidentity.opendst.intercept;

import static com.pingidentity.opendst.simulator.Node.currentNodeOrNull;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.pingidentity.opendst.simulator.Node;
import com.pingidentity.opendst.simulator.Simulator;
import com.pingidentity.opendst.simulator.Simulator.SimulationError;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.Return;

/**
 * Functional module for network simulation and instrumentation.
 * <p>
 * This class manages host name to IP address mapping (DNS) and routing within the simulation.
 * <p>
 * Network interception uses two JVM-global hooks installed at agent startup:
 * <ul>
 *   <li>{@link Socket#setSocketImplFactory} / {@link ServerSocket#setSocketFactory}: intercepts all {@code Socket}
 *   and {@code ServerSocket} construction (including JDK-internal calls such as the SSLSocket super-constructor
 *   chain) and provides a simulated {@code SocketImpl} when running inside a simulation node.</li>
 *   <li>{@link ResolverProvider} is a {@link InetAddressResolverProvider} registered via {@code ServiceLoader} that
 *   redirects DNS lookups to the simulated DNS registry when running inside a simulation node.</li>
 * </ul>
 */
public final class NetworkInterceptors {
    private final Simulator simulator;
    private final Map<InetAddress, String> addressToName;
    private final Map<String, Node> nameToNode;

    /** Maximum number of simulated nodes. */
    private static final int MAX_NODES = 100;

    /** Maximum number of IP address-to-hostname mappings. */
    private static final int MAX_ADDRESSES = 256;

    /**
     * Handle to {@code SocketImpl.createPlatformSocketImpl(boolean)} for creating real socket implementations when a
     * socket is constructed outside a simulation node (e.g. JDK-internal or JVM bootstrap code). Obtained
     * reflectively because the method is package-private.
     */
    private static final MethodHandle CREATE_PLATFORM_SOCKET_IMPL;

    static {
        try {
            var lookup = MethodHandles.privateLookupIn(SocketImpl.class, MethodHandles.lookup());
            CREATE_PLATFORM_SOCKET_IMPL = lookup.findStatic(
                    SocketImpl.class,
                    "createPlatformSocketImpl",
                    MethodType.methodType(SocketImpl.class, boolean.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a platform (real) {@link SocketImpl} for use outside simulation nodes.
     * This is what the JDK would normally create if no factory were installed.
     */
    @SuppressWarnings("deprecation")
    private static SocketImpl createPlatformSocketImpl(boolean server) {
        try {
            return (SocketImpl) CREATE_PLATFORM_SOCKET_IMPL.invokeExact(server);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create platform SocketImpl", e);
        }
    }

    public NetworkInterceptors(Simulator simulator) {
        this.simulator = requireNonNull(simulator);
        this.addressToName = new HashMap<>(MAX_ADDRESSES);
        this.nameToNode = new HashMap<>(MAX_NODES);
    }

    // --- DNS and Registry ---

    /** DNS resolver provider that uses the simulation's DNS registry. */
    public static final class ResolverProvider extends InetAddressResolverProvider {
        @Override
        public InetAddressResolver get(Configuration configuration) {
            var builtinResolver = configuration.builtinResolver();
            return new InetAddressResolver() {
                @Override
                public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                        throws UnknownHostException {
                    var node = currentNodeOrNull();
                    return node != null
                            ? Simulator.network().lookupByName(host)
                            : builtinResolver.lookupByName(host, lookupPolicy);
                }

                @Override
                public String lookupByAddress(byte[] addr) throws UnknownHostException {
                    var node = currentNodeOrNull();
                    return node != null
                            ? Simulator.network().lookupByAddress(InetAddress.getByAddress(addr))
                            : builtinResolver.lookupByAddress(addr);
                }
            };
        }

        @Override
        public String name() {
            return "SimulatedDNS";
        }
    }

    public void registerDns(String hostName, Node host) {
        var lowerCaseHostName = hostName.toLowerCase(ROOT);
        if (nameToNode.size() >= MAX_NODES && !nameToNode.containsKey(lowerCaseHostName)) {
            simulator.reportInternalError(new SimulationError("Maximum number of nodes reached"));
        }
        if (nameToNode.putIfAbsent(lowerCaseHostName, host) == null) {
            host.inetAddresses().forEach(address -> {
                if (!address.isLoopbackAddress()) {
                    if (addressToName.size() >= MAX_ADDRESSES && !addressToName.containsKey(address)) {
                        simulator.reportInternalError(new SimulationError("Maximum number of addresses reached"));
                    }
                    addressToName.put(address, lowerCaseHostName);
                }
            });
        }
    }

    public void unregisterDns(String hostName) {
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

    // --- Routing ---

    @SuppressWarnings({"deprecation", "removal"})
    public SocketImpl route(InetAddress from, InetAddress addr, int port)
            throws UnknownHostException, NoRouteToHostException {
        if (from.isLoopbackAddress() && !addr.isLoopbackAddress()) {
            throw new NoRouteToHostException();
        }
        return lookupHostByAddress(addr).route(from, addr, port);
    }

    public Map<String, Node> nodes() {
        return nameToNode;
    }

    // --- Static Interception Hooks ---

    @Intercepts("java.net.InetAddress#getLocalHost()")
    public static final class InetAddressGetLocalHost {
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static InetAddress onEnter() {
            var node = currentNodeOrNull();
            return node != null ? node.getLocalHost() : null;
        }

        @OnMethodExit
        public static void onExit(@Return(readOnly = false) InetAddress returned, @Enter InetAddress simulatedAddress) {
            if (simulatedAddress != null) {
                returned = simulatedAddress;
            }
        }
    }

    /**
     * Installs JVM-global socket factories and ByteBuddy advice for network interception.
     * <p>
     * The socket factories intercept all {@code Socket} and {@code ServerSocket} construction,
     * returning a simulated {@code SocketImpl} when running inside a simulation node, or a
     * platform socket implementation otherwise (for JDK-internal and bootstrap code).
     */
    @SuppressWarnings({"deprecation", "removal"})
    static AgentBuilder instrument(AgentBuilder agent) throws IOException {
        Socket.setSocketImplFactory(() -> {
            var node = currentNodeOrNull();
            return node != null ? node.newSocketImpl(false) : createPlatformSocketImpl(false);
        });
        ServerSocket.setSocketFactory(() -> {
            var node = currentNodeOrNull();
            return node != null ? node.newSocketImpl(true) : createPlatformSocketImpl(true);
        });
        return agent.type(named("java.net.InetAddress"))
                .transform((builder, _, _, _, _) -> builder.visit(to(InetAddressGetLocalHost.class)
                        .on(named("getLocalHost")
                                .and(isPublic())
                                .and(isStatic())
                                .and(returns(InetAddress.class))
                                .and(takesNoArguments()))))
                .asTerminalTransformation();
    }
}
