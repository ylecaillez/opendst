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

import java.io.InterruptedIOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A lock-based variable that supports blocking waits for value changes.
 * <p>
 * Used to coordinate producer-consumer communication in the simulated TCP
 * stack (e.g., tracking how many bytes have been written, sent, received,
 * and read).
 */
@SuppressWarnings("serial")
final class AsyncVar extends ReentrantLock {
    private final Condition condition = newCondition();
    private long changeCount;
    private long value;

    void await() throws InterruptedIOException {
        lock();
        try {
            long change = changeCount;
            while (change == changeCount) {
                condition.await();
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        } finally {
            unlock();
        }
    }

    boolean await(long nanos) throws InterruptedIOException {
        if (nanos == 0) {
            await();
            return true;
        }
        lock();
        try {
            long change = changeCount;
            while (nanos > 0 && change == changeCount) {
                nanos = condition.awaitNanos(nanos);
            }
            return change != changeCount;
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        } finally {
            unlock();
        }
    }

    void add(long value) {
        set(this.value + value);
    }

    void set(long value) {
        if (value != this.value) {
            this.value = value;
            signal();
        }
    }

    void signal() {
        lock();
        try {
            changeCount++;
            condition.signalAll();
        } finally {
            unlock();
        }
    }

    long get() {
        return value;
    }
}
