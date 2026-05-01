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

import static com.pingidentity.opendst.Node.currentNodeOrNull;
import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.asm.MemberSubstitution.relaxed;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.fieldType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isFinal;
import static net.bytebuddy.matcher.ElementMatchers.isPackagePrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.pingidentity.opendst.Plan.Segment;
import java.io.Serial;
import java.security.SecureRandomSpi;
import java.util.ArrayDeque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;
import net.bytebuddy.asm.Advice.Return;
import net.bytebuddy.asm.Advice.This;
import net.bytebuddy.description.method.MethodDescription.ForLoadedMethod;

/**
 * Functional module for randomness simulation and instrumentation.
 */
public final class RandomInterceptors {

    /**
     * Optional supplier of the next segment at each boundary, used by the nyx-lite engine to
     * deliver segments one at a time via shared memory. When non-null, overrides the plan's
     * built-in segment queue. Set by {@code NyxGuestEntry} before invoking {@code
     * OpenDSTExecutor.main()}. Static (not thread-local) because the supplier is called from
     * simulated virtual threads that do not inherit thread-locals from the main thread.
     */
    public static volatile java.util.function.Supplier<Segment> NYX_SEGMENT_SUPPLIER;

    /**
     * When true, {@link Source#next(int)} issues the deferred boot snapshot hypercall on the
     * very first draw, then re-seeds from {@link #NYX_SEGMENT_SUPPLIER}. Set by
     * {@code NyxGuestEntry} before invoking the simulation; cleared by {@code Source.next()}
     * before issuing the hypercall (the snapshot captures the {@code true} state so each
     * restore re-triggers it).
     */
    public static volatile boolean NYX_BOOT_SNAPSHOT_PENDING;

    public static final class Source extends Random {
        @Serial
        private static final long serialVersionUID = 1L;

        private final transient Simulator simulator;
        private final transient ArrayDeque<Segment> segments;
        private transient Segment currentSegment;
        private long iteration;
        private long nextIteration;
        int last;

        Source(Simulator simulator, List<Segment> segments) {
            this.simulator = simulator;
            this.segments = new ArrayDeque<>(segments);
            var segment = this.segments.removeFirst();
            this.currentSegment = segment;
            setSeed(segment.seed());
            nextIteration = segment.iteration();
        }

        /** {@return the number of time a random number has been generated} */
        public long iteration() {
            return iteration;
        }

        /**
         * {@return the next random number}
         *
         * @param bits size in bits of the random number to generate
         */
        public int nextBits(int bits) {
            return next(bits);
        }

        @Override
        protected int next(int bits) {
            assert bits > 0 && bits <= 32;
            // On the very first draw in nyx-lite mode, issue the deferred boot snapshot
            // so the host captures everything including plan parsing and node init.
            // After restore, iteration is still 0 and the flag is still true (snapshot
            // state), so this fires again — re-seeding from SHM for the new plan.
            if (iteration == 0 && NYX_BOOT_SNAPSHOT_PENDING) {
                NYX_BOOT_SNAPSHOT_PENDING = false;
                NyxSegmentHypercall.requestBootSnapshot();
                // Shim wrote segment 0 into INPUT; re-seed to override the plan's baked-in seed.
                var supplier = NYX_SEGMENT_SUPPLIER;
                if (supplier != null) {
                    var seg = supplier.get();
                    if (seg != null) {
                        currentSegment = seg;
                        nextIteration = seg.iteration();
                        setSeed(seg.seed());
                        segments.clear();
                    }
                }
            }
            iteration++;
            last = super.next(bits);
            simulator.hash(last);
            // Check boundary AFTER the draw so that a simulation making exactly
            // nextIteration draws crosses the boundary before it exits.
            if (iteration >= nextIteration) {
                this.simulator.checkSegmentHash(currentSegment);
                var nyxSupplier = NYX_SEGMENT_SUPPLIER;
                if (nyxSupplier != null) {
                    // Nyx-lite mode: the shim took a snapshot during checkSegmentHash's
                    // hypercall and wrote the next segment to INPUT. Read it now.
                    // The local segments queue is ignored — the shim owns segment delivery.
                    var segment = nyxSupplier.get();
                    if (segment == null) {
                        this.simulator.exitSimulation(Simulator.ExitReason.PLAN_OK);
                        throw new Simulator.SimulationError("Plan exhausted (nyx)");
                    }
                    currentSegment = segment;
                    nextIteration = segment.iteration();
                    setSeed(segment.seed());
                    // Drain the local queue so it stays consistent (nyx mode ignores it but
                    // it may be non-empty if the plan JSON carried multiple segments).
                    segments.clear();
                } else {
                    try {
                        var segment = segments.removeFirst();
                        currentSegment = segment;
                        nextIteration = segment.iteration();
                        assert nextIteration > iteration;
                        setSeed(segment.seed());
                    } catch (NoSuchElementException e) {
                        this.simulator.exitSimulation(Simulator.ExitReason.PLAN_OK);
                        throw new Simulator.SimulationError("Plan exhausted");
                    }
                }
            }
            return last;
        }
    }

    /** Overrides {@code java.util.ImmutableCollections#SALT32L}. */
    @SuppressWarnings("MissingJavadocMethod")
    @Intercepts("java.util.ImmutableCollections#SALT32L")
    public static long immutableCollectionsSalt32l()
            throws IllegalAccessException, ClassNotFoundException, NoSuchFieldException {
        var node = currentNodeOrNull();
        if (node != null) {
            return node.immutableCollectionsSalt32l();
        }
        var field = Class.forName("java.util.ImmutableCollections").getDeclaredField("SALT32L");
        field.setAccessible(true);
        return field.getLong(null);
    }

    /** Overrides {@code java.util.ImmutableCollections#REVERSE}. */
    @SuppressWarnings("MissingJavadocMethod")
    @Intercepts("java.util.ImmutableCollections#REVERSE")
    public static boolean immutableCollectionsReverse()
            throws IllegalAccessException, ClassNotFoundException, NoSuchFieldException {
        var node = currentNodeOrNull();
        if (node != null) {
            return node.immutableCollectionsReverse();
        }
        var field = Class.forName("java.util.ImmutableCollections").getDeclaredField("REVERSE");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    /** Overrides {@code java.security.SecureRandomSpi#engineSetSeed(byte[])}. */
    @Intercepts(value = "java.security.SecureRandomSpi#engineSetSeed(byte[])", noOp = true)
    public static final class SecureRandomSetSeed {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }
    }

    /** Overrides {@code java.security.SecureRandomSpi#engineNextBytes(byte[])}. */
    @Intercepts("java.security.SecureRandomSpi#engineNextBytes(byte[])")
    public static final class SecureRandomNextBytes {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Node node, @Argument(value = 0, readOnly = false) byte[] bytes) {
            if (node != null) {
                node.random().nextBytes(bytes);
            }
        }
    }

    /** Overrides {@link java.security.SecureRandom#getSeed(int)}. */
    @Intercepts("java.security.SecureRandom#getSeed(int)")
    public static final class SecureRandomGetSeed {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "ReassignedVariable"})
        public static void onExit(
                @Enter Node node, @Argument(value = 0) int numBytes, @Return(readOnly = false) byte[] bytes) {
            if (node != null) {
                bytes = new byte[numBytes];
                node.random().nextBytes(bytes);
            }
        }
    }

    /** Overrides {@code java.security.SecureRandomSpi#engineGenerateSeed(int)}. */
    @Intercepts("java.security.SecureRandomSpi#engineGenerateSeed(int)")
    public static final class SecureRandomGenerateSeed {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "ReassignedVariable"})
        public static void onExit(
                @Enter Node node, @Argument(value = 0) int numBytes, @Return(readOnly = false) byte[] bytes) {
            if (node != null) {
                bytes = new byte[numBytes];
                node.random().nextBytes(bytes);
            }
        }
    }

    /** Overrides {@link java.util.concurrent.ThreadLocalRandom#nextSeed()}. */
    @Intercepts("java.util.concurrent.ThreadLocalRandom#nextSeed()")
    public static final class ThreadLocalRandomNextSeedAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter() {
            return currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void intercept(@Enter Node node, @Return(readOnly = false) long seed) {
            if (node != null) {
                seed = node.random().nextLong();
            }
        }
    }

    /** Overrides {@link RandomGenerator#nextLong()}. */
    @Intercepts("java.util.random.RandomGenerator#nextLong()")
    public static final class RandomGeneratorNextLongAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@This RandomGenerator self) {
            return self instanceof Source ? null : currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onExit(@Enter Node node, @Return(readOnly = false) long nextLong) {
            if (node != null) {
                nextLong = node.random().nextLong();
            }
        }
    }

    /** Overrides {@code java.util.Random#next(int)}. */
    @Intercepts("java.util.Random#next(int)")
    public static final class RandomNextBitsAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@This Random self) {
            // To prevent stack-overflow, call the original implementation for Source.
            // ThreadLocalRandom next(int) original implementation delegates to getSeed() which is already overridden.
            return self instanceof Source || self instanceof ThreadLocalRandom ? null : currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onExit(@Enter Node node, @Argument(value = 0) int bits, @Return(readOnly = false) int next) {
            if (node != null) {
                next = node.random().nextBits(bits);
            }
        }
    }

    /** Overrides {@link java.util.Random#nextGaussian()}. */
    @Intercepts("java.util.Random#nextGaussian()")
    public static final class RandomNextGaussian {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@This Random self) {
            // To prevent stack-overflow, call the original implementation for Source.
            // ThreadLocalRandom next(int) original implementation delegates to getSeed() which is already overridden.
            return self instanceof Source ? null : currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onExit(@Enter Node node, @Return(readOnly = false) double out) {
            if (node != null) {
                out = node.random().nextGaussian();
            }
        }
    }

    /** Overrides {@link java.util.Random#nextGaussian(double, double)}. */
    @Intercepts("java.util.Random#nextGaussian(double,double)")
    public static final class RandomNextGaussianMeanStdDev {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Node onEnter(@This Random self) {
            // To prevent stack-overflow, call the original implementation for Source.
            // ThreadLocalRandom next(int) original implementation delegates to getSeed() which is already overridden.
            return self instanceof Source ? null : currentNodeOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({"MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable"})
        public static void onExit(
                @Enter Node node,
                @Argument(value = 0) double mean,
                @Argument(value = 1) double stddev,
                @Return(readOnly = false) double out) {
            if (node != null) {
                out = node.random().nextGaussian(mean, stddev);
            }
        }
    }

    static AgentBuilder instrument(AgentBuilder agent) {
        return agent.type(isSubTypeOf(SecureRandomSpi.class).and(not(isAbstract())))
                .transform((builder, _, _, _, _) -> builder.visit(
                                to(SecureRandomSetSeed.class).on(named("engineSetSeed")))
                        .visit(to(SecureRandomNextBytes.class)
                                .on(named("engineNextBytes").and(isProtected()).and(takesArguments(byte[].class))))
                        .visit(to(SecureRandomGenerateSeed.class).on(named("engineGenerateSeed"))))
                .asTerminalTransformation()
                .type(named("java.security.SecureRandom"))
                .transform((builder, _, _, _, _) -> builder.visit(to(SecureRandomGetSeed.class)
                        .on(named("getSeed").and(takesArguments(int.class)).and(returns(byte[].class)))))
                .asTerminalTransformation()
                .type(named("java.util.concurrent.ThreadLocalRandom"))
                .transform((builder, _, _, _, _) -> builder.visit(to(ThreadLocalRandomNextSeedAdvice.class)
                        .on(named("nextSeed")
                                .and(isPackagePrivate())
                                .and(isFinal())
                                .and(returns(long.class))
                                .and(takesNoArguments()))))
                .asTerminalTransformation()
                .type(named("java.util.SplittableRandom"))
                .transform((builder, _, _, _, _) -> builder.visit(to(ThreadLocalRandomNextSeedAdvice.class)
                        .on(named("nextSeed")
                                .and(isPrivate())
                                .and(returns(long.class))
                                .and(takesNoArguments()))))
                .asTerminalTransformation()
                .type(isSubTypeOf(Random.class))
                .transform((builder, _, _, _, _) -> builder.visit(to(RandomNextBitsAdvice.class)
                                .on(named("next")
                                        .and(isProtected())
                                        .and(takesArguments(int.class))
                                        .and(returns(int.class))))
                        .visit(to(RandomNextGaussian.class)
                                .on(named("nextGaussian")
                                        .and(takesNoArguments())
                                        .and(returns(double.class))))
                        .visit(to(RandomNextGaussianMeanStdDev.class)
                                .on(named("nextGaussian")
                                        .and(takesArguments(double.class, double.class))
                                        .and(returns(double.class)))))
                .asTerminalTransformation()
                .type(isSubTypeOf(RandomGenerator.class))
                .transform((builder, _, _, _, _) -> builder.visit(to(RandomGeneratorNextLongAdvice.class)
                        .on(named("nextLong").and(takesNoArguments()).and(returns(long.class)))))
                .asTerminalTransformation()
                .type(nameStartsWith("java.util.ImmutableCollections"))
                .transform((builder, _, _, _, _) -> {
                    try {
                        return builder.visit(relaxed()
                                .field(named("SALT32L")
                                        .and(isPrivate())
                                        .and(isStatic())
                                        .and(isFinal())
                                        .and(fieldType(long.class)))
                                .onRead()
                                .replaceWith(new ForLoadedMethod(
                                        RandomInterceptors.class.getMethod("immutableCollectionsSalt32l")))
                                .on(any()));
                    } catch (NoSuchMethodException ex) {
                        throw new Simulator.SimulationError(ex);
                    }
                })
                .type(nameStartsWith("java.util.ImmutableCollections"))
                .transform((builder, _, _, _, _) -> {
                    try {
                        return builder.visit(relaxed()
                                .field(named("REVERSE")
                                        .and(isPrivate())
                                        .and(isStatic())
                                        .and(isFinal())
                                        .and(fieldType(boolean.class)))
                                .onRead()
                                .replaceWith(new ForLoadedMethod(
                                        RandomInterceptors.class.getMethod("immutableCollectionsReverse")))
                                .on(any()));
                    } catch (NoSuchMethodException ex) {
                        throw new Simulator.SimulationError(ex);
                    }
                });
    }

    private RandomInterceptors() {
        // Prevent instantiation
    }
}
