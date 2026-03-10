/*
 * Copyright 2024-2026 Ping Identity Corporation
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

import java.util.concurrent.locks.Lock;

/**
 * Holds global simulation state and services shared across all components.
 * Initialized incrementally to handle circular dependencies.
 */
final class SimulationContext {
    private final Simulator simulator;
    private final Time.Scheduler scheduler;
    private final Randomness.Source random;
    private final Faults.Config faults;
    private final StateHasher hasher = new StateHasher();

    private volatile Network network;
    private volatile Faults.Injector faultInjector;
    private volatile ConsoleCapture logger;
    private volatile Lock lock;

    SimulationContext(Simulator simulator, Time.Scheduler scheduler, Randomness.Source random, Faults.Config faults) {
        this.simulator = simulator;
        this.scheduler = scheduler;
        this.random = random;
        this.faults = faults;
    }

    StateHasher hasher() {
        return hasher;
    }

    Simulator simulator() {
        return simulator;
    }

    Time.Scheduler scheduler() {
        return scheduler;
    }

    Randomness.Source random() {
        return random;
    }

    Faults.Config faults() {
        return faults;
    }

    Network network() {
        return network;
    }

    void setNetwork(Network network) {
        this.network = network;
    }

    Faults.Injector faultInjector() {
        return faultInjector;
    }

    void setFaultInjector(Faults.Injector faultInjector) {
        this.faultInjector = faultInjector;
    }

    ConsoleCapture logger() {
        return logger;
    }

    void setLogger(ConsoleCapture logger) {
        this.logger = logger;
    }

    Lock lock() {
        return lock;
    }

    void setLock(Lock lock) {
        this.lock = lock;
    }
}
