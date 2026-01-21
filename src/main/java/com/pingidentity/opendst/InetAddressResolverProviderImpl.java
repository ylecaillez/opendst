/*
 * Copyright 2025 Ping Identity Corporation
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

import static com.pingidentity.opendst.Simulator.machineOrNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * A simulated {@link InetAddressResolverProvider} that uses the simulated network stack for DNS resolution.
 * This provider is used to resolve hostnames to IP addresses within the simulation.
 */
public final class InetAddressResolverProviderImpl extends InetAddressResolverProvider {
    @Override
    public InetAddressResolver get(Configuration configuration) {
        return new NetResolver(configuration.builtinResolver());
    }

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    private record NetResolver(InetAddressResolver fallback) implements InetAddressResolver {
        @Override
        public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
            var machine = machineOrNull();
            return machine != null ? machine.lookupByName(host) : fallback.lookupByName(host, lookupPolicy);
        }

        @Override
        public String lookupByAddress(byte[] addr) throws UnknownHostException {
            var machine = machineOrNull();
            return machine != null ? machine.lookupByAddress(InetAddress.getByAddress(addr))
                                   : fallback.lookupByAddress(addr);
        }
    }
}
