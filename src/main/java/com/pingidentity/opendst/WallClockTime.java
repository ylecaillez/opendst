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

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import com.pingidentity.opendst.Simulator.SimulationError;

/**
 * Provides access to foreign time functions {@code gettimeofday} and {@code clock_gettime}.
 * These methods are used to retrieve current time in milliseconds and high-resolution monotonic time in nanoseconds,
 * respectively.
 * <p>
 * Given that both {@link System#currentTimeMillis()} and {@link System#nanoTime()} are overwritten by the
 * {@link SimulatorAgent}, this class provides the unique way to get access to the real wall-clock time.
 */
@SuppressWarnings("restricted")
public final class WallClockTime {
    private static final int CLOCK_MONOTONIC = 1; // Common value on Linux for clock_gettime
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup STDLIB_LOOKUP = LINKER.defaultLookup();
    /** struct timeval, assuming 64-bit time_t and suseconds_t. */
    private static final MemoryLayout TIMEVAL_LAYOUT = structLayout(JAVA_LONG.withName("tv_sec"),
                                                                    JAVA_LONG.withName("tv_usec"));
    private static final long TV_SEC_OFFSET = TIMEVAL_LAYOUT.byteOffset(groupElement("tv_sec"));
    private static final long TV_USEC_OFFSET = TIMEVAL_LAYOUT.byteOffset(groupElement("tv_usec"));
    /** struct timespec, assuming 64-bit time_t and long for nanoseconds. */
    private static final MemoryLayout TIMESPEC_LAYOUT = structLayout(JAVA_LONG.withName("tv_sec"),
                                                                     JAVA_LONG.withName("tv_nsec"));
    private static final long TS_SEC_OFFSET = TIMESPEC_LAYOUT.byteOffset(groupElement("tv_sec"));
    private static final long TS_NSEC_OFFSET = TIMESPEC_LAYOUT.byteOffset(groupElement("tv_nsec"));

    private static final MethodHandle GETTIMEOFDAY;
    private static final MethodHandle CLOCK_GETTIME;

    static {
        // gettimeofday: int gettimeofday(struct timeval *tv, struct timezone *tz);
        var getTimeOfDayAddr =
            STDLIB_LOOKUP.find("gettimeofday")
                         .orElseThrow(() -> new SimulationError("Cannot find symbol: gettimeofday"));
        var getTimeOfDayDesc = FunctionDescriptor.of(JAVA_INT,
                                                     ADDRESS,  // struct timeval*
                                                     ADDRESS); // struct timezone*
        GETTIMEOFDAY = LINKER.downcallHandle(getTimeOfDayAddr, getTimeOfDayDesc);

        // clock_gettime: int clock_gettime(clockid_t clk_id, struct timespec *tp);
        var clockGetTimeAddr =
                STDLIB_LOOKUP.find("clock_gettime")
                             .orElseThrow(() -> new SimulationError("Cannot find symbol: clock_gettime"));
        var clockGetTimeDesc = FunctionDescriptor.of(JAVA_INT,
                                                     JAVA_INT, // clockid_t (int)
                                                     ADDRESS); // struct timespec*
        CLOCK_GETTIME = LINKER.downcallHandle(clockGetTimeAddr, clockGetTimeDesc);
    }

    /**
     * Gets current time as milliseconds since epoch using native gettimeofday.
     * (Similar purpose to System.currentTimeMillis())
     *
     * @return Milliseconds since epoch, or -1 on error.
     */
    public static long wallClockCurrentTimeMillis() {
        try (var arena = Arena.ofConfined()) {
            var timevalSegment = arena.allocate(TIMEVAL_LAYOUT);
            int ret = (int) GETTIMEOFDAY.invokeExact(timevalSegment, MemorySegment.NULL);
            if (ret != 0) {
                throw new SimulationError("gettimeofday failed with return code: " + ret);
            }
            long seconds = timevalSegment.get(JAVA_LONG, TV_SEC_OFFSET);
            long microseconds = timevalSegment.get(JAVA_LONG, TV_USEC_OFFSET);
            return SECONDS.toMillis(seconds) + MICROSECONDS.toMillis(microseconds);
        } catch (Throwable e) {
            throw new SimulationError("Error calling gettimeofday: " + e);
        }
    }

    /**
     * Gets high-resolution monotonic time using native clock_gettime.
     * (Similar purpose to {@link System#nanoTime()})
     * Note: Since the epoch/origin is arbitrary, only the elapsed time is meaningful.
     *
     * @return Nanosecond timestamp from monotonic clock, or -1 on error.
     */
    public static long wallClockNanoTime() {
        try (var arena = Arena.ofConfined()) {
            var timespecSegment = arena.allocate(TIMESPEC_LAYOUT);
            int ret = (int) CLOCK_GETTIME.invokeExact(CLOCK_MONOTONIC, timespecSegment);
            if (ret != 0) {
                throw new SimulationError("clock_gettime failed with return code: " + ret);
            }
            long seconds = timespecSegment.get(JAVA_LONG, TS_SEC_OFFSET);
            long nanoseconds = timespecSegment.get(JAVA_LONG, TS_NSEC_OFFSET);
            return SECONDS.toNanos(seconds) + nanoseconds;
        } catch (Throwable e) {
            throw new SimulationError("Error calling clock_gettime", e);
        }
    }

    private WallClockTime() {
        // Prevent instantiation
    }
}
