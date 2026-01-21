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

import static com.pingidentity.opendst.Simulator.REDIRECT_CONSTRUCTORS_OF;
import static com.pingidentity.opendst.Simulator.machineOrNull;
import static com.pingidentity.opendst.WallClockTime.wallClockCurrentTimeMillis;
import static com.pingidentity.opendst.WallClockTime.wallClockNanoTime;
import static java.lang.Boolean.FALSE;
import static java.lang.String.format;
import static java.lang.ThreadLocal.withInitial;
import static java.lang.classfile.ClassHierarchyResolver.ofClassLoading;
import static java.lang.classfile.ClassTransform.transformingMethods;
import static java.lang.classfile.MethodTransform.transformingCode;
import static java.lang.classfile.Opcode.DUP;
import static java.lang.classfile.Opcode.INVOKESPECIAL;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.bytebuddy.asm.Advice.to;
import static net.bytebuddy.asm.MemberSubstitution.relaxed;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.fieldType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isFinal;
import static net.bytebuddy.matcher.ElementMatchers.isPackagePrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFile.ClassHierarchyResolverOption;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.security.ProtectionDomain;
import java.security.SecureRandomSpi;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

import com.pingidentity.opendst.Simulator.Machine;
import com.pingidentity.opendst.Simulator.SimulationError;
import com.pingidentity.opendst.Simulator.SourceOfRandomness;
import com.pingidentity.opendst.Simulator.SystemExitError;
import com.pingidentity.opendst.Simulator.VirtualThreadUnblocker;

import jdk.jfr.FlightRecorder;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder.Default;
import net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.InjectionStrategy.UsingReflection;
import net.bytebuddy.agent.builder.AgentBuilder.Listener.StreamWriting;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.OnNonDefaultValue;
import net.bytebuddy.asm.Advice.Return;
import net.bytebuddy.asm.Advice.This;
import net.bytebuddy.description.method.MethodDescription.ForLoadedMethod;
import net.bytebuddy.implementation.Implementation.Context.Disabled.Factory;
import net.bytebuddy.implementation.StubMethod;

/**
 * Intercepts key JDK methods to provide a deterministic execution environment.
 * <p>
 * This agent is intended to be used with the {@link Simulator}. There are two kind of instrumentation made here:
 * <ul>
 *     <li>
 *     The in-place instrumentation: Changes implementation of JDK methods subject to non-determinism to be able to
 *     choose between the execution of the original code (when invoked outside the simulator) or a deterministic
 *     replacement (when invoked from inside the simulator). In-place instrumentation relies on bytebuddy's
 *     {@link Advice} and have different constraints depending on whether the instrumented method is {@code native}:
 *     <ul>
 *         <li>
 *         Instrumentation of java bytecode method: bytecode are injected before and after the original method body.
 *         The "before" part checks whether the invocation is made from a simulator context. If it is not, the
 *         original method body is executed. Otherwise, the original method body is skipped and replaced by the code
 *         contained in the {@link Advice}.
 *         </li>
 *         <li>
 *         Instrumentation of native method: Native methods don't have java bytecode to instrument. As such, it is
 *         not possible to invoke the original implementation from the injected bytecode. The injected bytecode must
 *         then provide an implementation when invoked inside the simulator (e.g by invoking the simulator) but also
 *         when it's invoked outside simulator, by re-implementing the original behavior.
 *         </li>
 *     </ul>
 *     </li>
 *     <li>
 *     The call-site instrumentation: For the cases where it is not possible to perform an in-place instrumentation
 *     (e.g: {@code new Thread(runnable)}), this instrumentation simply replaces the original {@code new} operator by
 *     a static method invocation to the simulator. The simulator is then in charge to invoke the original
 *     constructor (when invoked outside the simulator) or a deterministic replacement (when invoked from inside the
 *     simulator).
 *     </li>
 * </ul>
 */
public final class SimulatorAgent {
    public static final String AGENT_PROPERTY = "com.pingidentity.opendst.simulator.agent";
    public static final ThreadLocal<Boolean> IS_DAEMON = withInitial(() -> FALSE);
    public static final ThreadLocal<Boolean> IS_VIRTUAL = withInitial(() -> FALSE);

    /**
     * Installs bytecode transformation.
     *
     * @param agentArgs       Arguments for the agent, not used here.
     * @param instrumentation The instrumentation instance to use for transforming classes.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("Simulator Agent loading...");
        new Default().disableClassFormatChanges()
                     .enableNativeMethodPrefix("native")
                     .with(new ByteBuddy().with(Factory.INSTANCE))
                     .with(InitializationStrategy.NoOp.INSTANCE)
                     .with(UsingReflection.INSTANCE)
                     .with(RedefinitionStrategy.REDEFINITION)
                     .with(TypeStrategy.Default.REDEFINE)
                     .with(StreamWriting.toSystemError().withErrorsOnly())
                     .ignore(none())
                     /** {@link System#currentTimeMillis()} and {@link System#nanoTime()} */
                     .type(named("java.lang.System"))
                     .transform((builder, _, _, _, _) ->
                            builder.method(named("currentTimeMillis").or(named("nanoTime")))
                                   .intercept(to(TimeAdvice.class).wrap(StubMethod.INSTANCE)))
                     .asTerminalTransformation()
                     /** {@link Runtime#exit(int)} */
                     .type(named("java.lang.Runtime"))
                     .transform((builder, _, _, _, _) ->
                            builder.method(named("exit")).intercept(
                                    to(RuntimeExitAdvice.class).wrap(StubMethod.INSTANCE)))
                     /** {@link VM#getNanoTimeAdjustment(long)}  } */
                     .type(named("jdk.internal.misc.VM"))
                     .transform((builder, _, _, _, _) ->
                            builder.method(named("getNanoTimeAdjustment"))
                                   .intercept(to(VMGetNanoTimeAdjustementAdvice.class)
                                                    .wrap(StubMethod.INSTANCE)))
                     .asTerminalTransformation()
                     /** {@link FlightRecorder#addPeriodicEvent(Class, Runnable)} */
                     .type(named("jdk.jfr.FlightRecorder"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(FlightRecorderAddPeriodicEventAdvice.class).on(named("addPeriodicEvent"))))
                     .asTerminalTransformation()
                     /** {@link SecureRandomSpi#engineSetSeed(byte[])},
                      *  {@link SecureRandomSpi#engineNextBytes(byte[])} and
                      *  {@link SecureRandomSpi#engineGenerateSeed(int)} */
                     .type(isSubTypeOf(SecureRandomSpi.class).and(not(isAbstract())))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(SecureRandomSetSeed.class).on(named("engineSetSeed")))
                                   .visit(to(SecureRandomNextBytes.class).on(
                                           named("engineNextBytes").and(isProtected())
                                                                   .and(takesArguments(byte[].class))))
                                   .visit(to(SecureRandomGenerateSeed.class)
                                                  .on(named("engineGenerateSeed"))))
                     .asTerminalTransformation()
                     /** {@link SecureRandom#getSeed(int)} */
                     .type(named("java.security.SecureRandom"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(SecureRandomGetSeed.class).on(
                                    named("getSeed").and(takesArguments(int.class))
                                                    .and(returns(byte[].class)))))
                     .asTerminalTransformation()
                     /** {@link ThreadLocalRandom#nextSeed()} */
                     .type(named("java.util.concurrent.ThreadLocalRandom"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(ThreadLocalRandomNextSeedAdvice.class).on(
                                    named("nextSeed").and(isPackagePrivate())
                                                     .and(isFinal())
                                                     .and(returns(long.class))
                                                     .and(takesNoArguments()))))
                     .asTerminalTransformation()
                     /** {@link SplittableRandom#nextSeed()} */
                     .type(named("java.util.SplittableRandom"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(ThreadLocalRandomNextSeedAdvice.class).on(
                                    named("nextSeed").and(isPrivate())
                                                     .and(returns(long.class))
                                                     .and(takesNoArguments()))))
                     .asTerminalTransformation()
                     /** {@link Random#next(int)} */
                     .type(isSubTypeOf(Random.class))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(RandomNextBitsAdvice.class).on(
                                           named("next").and(isProtected())
                                                        .and(takesArguments(int.class))
                                                        .and(returns(int.class))))
                                   .visit(to(RandomNextGaussian.class).on(
                                           named("nextGaussian").and(takesNoArguments())
                                                                .and(returns(double.class))))
                                   .visit(to(RandomNextGaussianMeanStdDev.class).on(
                                           named("nextGaussian").and(takesArguments(double.class, double.class))
                                                                .and(returns(double.class)))))
                     .asTerminalTransformation()
                     /** {@link RandomGenerator#nextLong()} */
                     .type(isSubTypeOf(RandomGenerator.class))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(RandomGeneratorNextLongAdvice.class).on(
                                    named("nextLong").and(takesNoArguments()).and(returns(long.class)))))
                     .asTerminalTransformation()
                     .type(isSubTypeOf(Path.class))
                     .transform((builder, _, _, _, _) ->
                        builder.visit(to(PathWatchKeyAdvice.class).on(named("register"))))
                     .asTerminalTransformation()
                     /** {@link FileSystem#newWatchService()} } */
                     .type(isSubTypeOf(FileSystem.class))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(FileSystemWatchServiceAdvice.class).on(named("newWatchService"))))
                     .asTerminalTransformation()
                     /** {@link ThreadBuilders#newVirtualThread(Executor, String, int, Runnable)} */
                     .type(named("java.lang.ThreadBuilders"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(NewVirtualThreadAdvice.class).on(named("newVirtualThread"))))
                     .asTerminalTransformation()
                     /** {@link Thread#setDaemon(boolean)} */
                     .type(named("java.lang.Thread"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(ThreadSetDaemonAdvice.class).on(named("setDaemon"))))
                     .asTerminalTransformation()
                     /** ThreadIdentifiers#next */
                     .type(named("java.lang.Thread$ThreadIdentifiers"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(ThreadIdentifiersAdvice.class).on(named("next"))))
                     .asTerminalTransformation()
                     /** {@link VirtualThread#schedule(Runnable, long, TimeUnit)} */
                     .type(named("java.lang.VirtualThread"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(VThreadScheduleAdvice.class)
                                                .on(named("schedule").and(takesArguments(Runnable.class,
                                                                                         long.class,
                                                                                         TimeUnit.class))))
                                   .method(named("unblockVirtualThreads"))
                                   .intercept(to(UnblockVirtualThreadInterceptor.class)
                                                    .wrap(StubMethod.INSTANCE))
                     )
                     .asTerminalTransformation()
                     /** {@link Executors#defaultThreadFactory()} */
                     .type(named("java.util.concurrent.Executors"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(ExecutorsDefaultThreadFactoryAdvice.class)
                                                .on(named("defaultThreadFactory"))))
                     .asTerminalTransformation()
                     /** {@link Runtime#addShutdownHook(Thread)} and {@link Runtime#removeShutdownHook(Thread)} */
                     .type(named("java.lang.Runtime"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(RuntimeAddShutdownHookAdvice.class)
                                                .on(named("addShutdownHook")))
                                   .visit(to(RuntimeRemoveShutdownHookAdvice.class)
                                                .on(named("removeShutdownHook"))))
                     .asTerminalTransformation()
                     /** {@link InetAddress#getLocalHost()} */
                     .type(named("java.net.InetAddress"))
                     .transform((builder, _, _, _, _) ->
                            builder.visit(to(InetAddressGetLocalHost.class)
                                                .on(named("getLocalHost").and(isPublic())
                                                                         .and(isStatic())
                                                                         .and(returns(InetAddress.class))
                                                                         .and(takesNoArguments()))))
                     .asTerminalTransformation()
                     /** Intercepts {@link ImmutableCollections#SALT32L} */
                     .type(nameStartsWith("java.util.ImmutableCollections"))
                     .transform((builder, _, _, _, _) -> {
                         try {
                             return builder.visit(relaxed().field(named("SALT32L").and(isPrivate())
                                                                                  .and(isStatic())
                                                                                  .and(isFinal())
                                                                                  .and(fieldType(long.class)))
                                                           .onRead()
                                                           .replaceWith(new ForLoadedMethod(SimulatorAgent.class
                                                                .getMethod("immutableCollectionsSalt32l")))
                                                           .on(any())
                            );
                         } catch (NoSuchMethodException ex) {
                             throw new SimulationError(ex);
                         }
                     })
                     /** Intercepts {@link ImmutableCollections#REVERSE} */
                     .type(nameStartsWith("java.util.ImmutableCollections"))
                     .transform((builder, _, _, _, _) -> {
                         try {
                             return builder.visit(relaxed().field(named("REVERSE").and(isPrivate())
                                                                                  .and(isStatic())
                                                                                  .and(isFinal())
                                                                                  .and(fieldType(boolean.class)))
                                                           .onRead()
                                                           .replaceWith(new ForLoadedMethod(SimulatorAgent.class
                                                                .getMethod("immutableCollectionsReverse")))
                                                           .on(any())
                             );
                         } catch (NoSuchMethodException ex) {
                             throw new SimulationError(ex);
                         }
                     })
                     .installOn(instrumentation);

        instrumentation.addTransformer(new CallSiteInstrumentation("com.pingidentity.opendst.Simulator"));

        System.setProperty(AGENT_PROPERTY, "true");
        System.out.println("Simulator agent installed successfully");
    }

    /** Overrides {@link java.util.ImmutableCollections#SALT32L}. */
    @SuppressWarnings("JavadocMethod")
    public static long immutableCollectionsSalt32l()
            throws IllegalAccessException, ClassNotFoundException, NoSuchFieldException {
        var machine = machineOrNull();
        if (machine != null) {
            return machine.immutableCollectionsSalt32l();
        }
        var field = Class.forName("java.util.ImmutableCollections").getDeclaredField("SALT32L");
        field.setAccessible(true);
        return field.getLong(null);
    }

    /** Overrides {@link java.util.ImmutableCollections#REVERSE}. */
    @SuppressWarnings("JavadocMethod")
    public static boolean immutableCollectionsReverse()
            throws IllegalAccessException, ClassNotFoundException, NoSuchFieldException {
        var machine = machineOrNull();
        if (machine != null) {
            return machine.immutableCollectionsReverse();
        }
        var field = Class.forName("java.util.ImmutableCollections").getDeclaredField("REVERSE");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    /** Overrides {@link Executors#defaultThreadFactory()}. */
    public static final class ExecutorsDefaultThreadFactoryAdvice {
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void intercept(@Enter Machine machine,
                                     @Return(readOnly = false) ThreadFactory threadFactory) throws Throwable {
            if (machine != null) {
                threadFactory = machine.executorsDefaultThreadFactory();
            }
        }
    }

    /** Overrides {@link Runtime#addShutdownHook(Thread)}. */
    public static final class RuntimeAddShutdownHookAdvice {
        @SuppressWarnings("MissingJavadocMethod")
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Machine onEnter() {
            return machineOrNull();
        }

        @SuppressWarnings("MissingJavadocMethod")
        @Advice.OnMethodExit
        public static void onExit(@Enter Machine machine, @Argument(value = 0, readOnly = true) Thread hook) {
            if (machine != null) {
                machine.addShutdownHook(hook);
            }
        }
    }

    /** Overrides {@link Runtime#removeShutdownHook(Thread)}. */
    public static final class RuntimeRemoveShutdownHookAdvice {
        @SuppressWarnings("MissingJavadocMethod")
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Machine onEnter() {
            return machineOrNull();
        }

        @SuppressWarnings("MissingJavadocMethod")
        @OnMethodExit
        public static void onExit(@Enter Machine machine,
                                  @Argument(value = 0, readOnly = true) Thread hook,
                                  @Return(readOnly = false) boolean removed) {
            if (machine != null) {
                removed = machine.removeShutdownHook(hook);
            }
        }
    }

    /** Overrides {@link ThreadLocalRandom#nextSeed()}. */
    public static final class ThreadLocalRandomNextSeedAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void intercept(@Enter Machine machine, @Return(readOnly = false) long seed) {
            if (machine != null) {
                seed = machine.sourceOfRandomness().nextLong();
            }
        }
    }

    /** Overrides {@link InetAddress#getLocalHost()}. */
    public static final class InetAddressGetLocalHost {
        @SuppressWarnings("MissingJavadocMethod")
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static InetAddress onEnter() throws UnknownHostException {
            var machine = machineOrNull();
            return machine != null ? machine.getLocalHost() : null;
        }

        @SuppressWarnings("MissingJavadocMethod")
        @Advice.OnMethodExit
        public static void onExit(@Advice.Return(readOnly = false) InetAddress returned,
                                  @Advice.Enter InetAddress simulatedAddress) {
            if (simulatedAddress != null) {
                returned = simulatedAddress;
            }
        }
    }

    /**
     * Overrides {@link System#currentTimeMillis()} and {@link System#nanoTime()}.
     * <p>
     * This advice is modifying native methods. Native methods are specific as they per definition, don't have java
     * bytecode to instrument. As such, it is not possible to invoke the original implementation from the injected
     * bytecode. As such the injected bytecode must provide an implementation for the deterministic case (by getting
     * the time from the simulator) but also for the normal case where the real wall-clock is needed.
     */
    public static final class TimeAdvice {
        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void intercept(@Advice.Origin Method method, @Return(readOnly = false) long out) {
            var machine = machineOrNull();
            out = machine != null ? "currentTimeMillis".equals(method.getName()) ? machine.currentTimeMillis()
                                                                                 : machine.nanoTime()
                                  : "currentTimeMillis".equals(method.getName()) ? wallClockCurrentTimeMillis()
                                                                                 : wallClockNanoTime();
        }
    }

    /**
     * Overrides {@link jdk.internal.misc.VM#getNanoTimeAdjustment(long)}.
     * <p>
     * This advice is also modifying a native method. See @{@link TimeAdvice}
     */
    public static final class VMGetNanoTimeAdjustementAdvice {
        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void intercept(@Argument(value = 0, readOnly = true) long offsetInSeconds,
                                     @Return(readOnly = false) long returnedTime) {
            var machine = machineOrNull();
            var nowMs = machine != null ? machine.currentTimeMillis() : wallClockCurrentTimeMillis();
            returnedTime = MILLISECONDS.toNanos(nowMs - SECONDS.toMillis(offsetInSeconds));
        }
    }

    /** Overrides {@link jdk.jfr.FlightRecorder#addPeriodicEvent(Class, Runnable)}. */
    @NoOp("JFR is really low-level but maybe the periodicity aspect could be implemented on top of the run loop")
    public static final class FlightRecorderAddPeriodicEventAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }
    }

    /** Overrides {@link Runtime#exit(int)}. */
    public static final class RuntimeExitAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod" })
        public static void onExit(@Enter Machine machine, @Argument(value = 0) int exitCode) {
            if (machine != null) {
                machine.exit(exitCode);
                throw new SystemExitError(exitCode);
            }
        }
    }

    /** Overrides {@code java.security.SecureRandomSpi#engineSetSeed(byte[])}. */
    @NoOp
    public static final class SecureRandomSetSeed {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }
    }

    /** Overrides {@code java.security.SecureRandomSpi#engineNextBytes(byte[])}. */
    public static final class SecureRandomNextBytes {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Machine machine, @Argument(value = 0, readOnly = false) byte[] bytes) {
            if (machine != null) {
                machine.sourceOfRandomness().nextBytes(bytes);
            }
        }
    }

    /** Overrides {@link RandomGenerator#nextLong()}. */
    public static final class RandomGeneratorNextLongAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter(@This RandomGenerator self) {
            return self instanceof SourceOfRandomness ? null : machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable" })
        public static void onExit(@Enter Machine machine, @Return(readOnly = false) long nextLong) {
            if (machine != null) {
                nextLong = machine.sourceOfRandomness().nextLong();
            }
        }
    }

    /** Overrides {@code Random#next(int)}. */
    public static final class RandomNextBitsAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter(@This Random self) {
            // To prevent stack-overflow, call the original implementation for SourceOfRandomness.
            // ThreadLocalRandom next(int) original implementation delegates to getSeed() which is already overridden.
            return self instanceof SourceOfRandomness || self instanceof ThreadLocalRandom ? null
                                                                                           : machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable" })
        public static void onExit(@Enter Machine machine,
                                  @Argument(value = 0) int bits,
                                  @Return(readOnly = false) int next) {
            if (machine != null) {
                next = machine.sourceOfRandomness().nextBits(bits);
            }
        }
    }

    /** Overrides {@link Random#nextGaussian()}. */
    public static final class RandomNextGaussian {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter(@This Random self) {
            // To prevent stack-overflow, call the original implementation for SourceOfRandomness.
            // ThreadLocalRandom next(int) original implementation delegates to getSeed() which is already overridden.
            return self instanceof SourceOfRandomness ? null : machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable" })
        public static void onExit(@Enter Machine machine, @Return(readOnly = false) double out) {
            if (machine != null) {
                out = machine.sourceOfRandomness().nextGaussian();
            }
        }
    }

    /** Overrides {@link Random#nextGaussian(double, double)}. */
    public static final class RandomNextGaussianMeanStdDev {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter(@This Random self) {
            // To prevent stack-overflow, call the original implementation for SourceOfRandomness.
            // ThreadLocalRandom next(int) original implementation delegates to getSeed() which is already overridden.
            return self instanceof SourceOfRandomness ? null : machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable" })
        public static void onExit(@Enter Machine machine,
                                  @Argument(value = 0) double mean,
                                  @Argument(value = 1) double stddev,
                                  @Return(readOnly = false) double out) {
            if (machine != null) {
                out = machine.sourceOfRandomness().nextGaussian(mean, stddev);
            }
        }
    }

    /** Overrides {@link java.security.SecureRandom#getSeed(int)}. */
    public static final class SecureRandomGetSeed {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ParameterCanBeLocal", "ReassignedVariable" })
        public static void onExit(@Enter Machine machine,
                                  @Argument(value = 0) int numBytes,
                                  @Return(readOnly = false) byte[] bytes) {
            if (machine != null) {
                bytes = new byte[numBytes];
                machine.sourceOfRandomness().nextBytes(bytes);
            }
        }
    }

    /** Overrides {@code java.security.SecureRandomSpi#engineGenerateSeed(int)}. */
    public static final class SecureRandomGenerateSeed {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ParameterCanBeLocal", "ReassignedVariable" })
        public static void onExit(@Enter Machine machine,
                                  @Argument(value = 0) int numBytes,
                                  @Return(readOnly = false) byte[] bytes) {
            if (machine != null) {
                bytes = new byte[numBytes];
                machine.sourceOfRandomness().nextBytes(bytes);
            }
        }
    }

    /**
     * Overrides {@link java.nio.file.Path#register(WatchService, Kind[])} and
     * {@link java.nio.file.Path#register(WatchService, Kind[], Modifier...)}.
     */
    @NoOp(value = "Future improvement could schedule periodic event on the simulator's run-loop and generate fake "
            + "WatchEvent.")
    public static final class PathWatchKeyAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ReassignedVariable", "ParameterCanBeLocal", "UnusedAssignment" })
        public static void onExit(@Enter Machine machine, @This Path path, @Return(readOnly = false) WatchKey key) {
            if (machine != null) {
                key = new NoOpWatchKey(path);
            }
        }

        /** A fake {@link WatchKey} which always returns empty events. */
        public static final class NoOpWatchKey implements WatchKey {
            private final Watchable watchable;
            private boolean valid = true;

            /**
             * Creates a new fake {@link WatchKey}.
             *
             * @param watchable The resource being watched
             */
            public NoOpWatchKey(Watchable watchable) {
                this.watchable = watchable;
            }

            @Override
            public boolean isValid() {
                return valid;
            }

            @Override
            public List<WatchEvent<?>> pollEvents() {
                return List.of();
            }

            @Override
            public boolean reset() {
                boolean wasValid = valid;
                valid = false;
                return wasValid;
            }

            @Override
            public void cancel() {
                valid = false;
            }

            @Override
            public Watchable watchable() {
                return watchable;
            }
        }
    }

    public static final class FileSystemWatchServiceAdvice {
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ReassignedVariable", "ParameterCanBeLocal", "UnusedAssignment" })
        public static void onExit(@Enter Machine machine, @Return(readOnly = false) WatchService service) {
            if (machine != null) {
                service = new NoOpWatchService();
            }
        }

        public static final class NoOpWatchService implements WatchService {
            private final CountDownLatch latch = new CountDownLatch(1);
            private boolean closed;

            @Override
            public void close() {
                closed = true;
                latch.countDown();
            }

            @Override
            public WatchKey poll() {
                if (closed) {
                    throw new ClosedWatchServiceException();
                }
                return null;
            }

            @Override
            public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
                latch.await(timeout, unit);
                throw new ClosedWatchServiceException();
            }

            @Override
            public WatchKey take() throws InterruptedException {
                latch.await();
                throw new ClosedWatchServiceException();
            }
        }
    }

    /** Overrides {@link ThreadBuilders#newVirtualThread(Executor, String, int, Runnable)}. */
    public static final class NewVirtualThreadAdvice {
        @OnMethodEnter
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter(@Argument(value = 0, readOnly = false) Executor executor) {
            var machine = machineOrNull();
            if (machine != null) {
                executor = machine::scheduleNow;
            }
            return machine;
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Machine machine, @Return Thread thread) {
            if (machine != null) {
                machine.attachThread(thread);
            }
        }
    }

    /** Overrides {@link Thread#setDaemon(boolean)}. */
    public static final class ThreadSetDaemonAdvice {
        @OnMethodEnter
        @SuppressWarnings({ "MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable" })
        public static void onEnter(@Argument(value = 0, readOnly = false) boolean on) {
            var machine = machineOrNull();
            if (machine != null) {
                // false is not legal for virtual threads
                on = true;
            }
        }
    }

    /** Overrides {@link ThreadIdentifiers#next()}. */
    public static final class ThreadIdentifiersAdvice {
        public static final AtomicLong ID = new AtomicLong(10_000);
        @OnMethodEnter(skipOn = OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings({ "MissingJavadocMethod", "ParameterCanBeLocal", "UnusedAssignment", "ReassignedVariable" })
        public static void onExit(@Enter Machine machine, @Return(readOnly = false) long next) {
            if (machine != null) {
                next = ID.getAndIncrement();
            }
        }
    }

    /** Overrides {@link VirtualThread#schedule(Runnable, long, TimeUnit)}. */
    public static final class VThreadScheduleAdvice {
        @OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        @SuppressWarnings("MissingJavadocMethod")
        public static Machine onEnter() {
            return machineOrNull();
        }

        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit(@Enter Machine machine,
                                  @Argument(value = 0, readOnly = true) Runnable task,
                                  @Argument(value = 1, readOnly = true) long delay,
                                  @Argument(value = 2, readOnly = true) TimeUnit unit,
                                  @Return(readOnly = false) Future<?> future) {
            if (machine != null) {
                future = machine.scheduleAfterDelay(task, delay, unit);
            }
        }
    }

    /** Overrides {@link VirtualThread#unblockVirtualThreads()}. */
    public static final class UnblockVirtualThreadInterceptor {
        @OnMethodExit
        @SuppressWarnings("MissingJavadocMethod")
        public static void onExit() {
            VirtualThreadUnblocker.unblockVirtualThreads();
        }
    }

    private static final class CallSiteInstrumentation implements ClassFileTransformer {
        private final ClassDesc targetClassDesc;

        private CallSiteInstrumentation(String targetClassName) {
            this.targetClassDesc = ClassDesc.of(targetClassName);
        }

        @Override
        public byte[] transform(ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            // Use the original classloader to resolve class hierarchy rather than the app class loader, which would
            // force these classes to be shared across all nodes.
            var classFile = loader != null ? ClassFile.of(ClassHierarchyResolverOption.of(ofClassLoading(loader)))
                                           : ClassFile.of();
            // Do not instrument the JDK classes loaded by the bootstrap classloader (except the ThreadBuilders) nor
            // the simulator class
            return (className.startsWith("java/lang/ThreadBuilders") || loader != null)
                   ? classFile.transformClass(classFile.parse(classfileBuffer), transformMethod())
                   : null;
        }

        private ClassTransform transformMethod() {
            return transformingMethods(transformingCode(new CodeTransform() {
                private ClassEntry instantiated;
                private boolean skipDup;

                @Override
                public void accept(CodeBuilder builder, CodeElement element) {
                    switch (element) {
                        case NewObjectInstruction i -> {
                            if (REDIRECT_CONSTRUCTORS_OF.contains(i.className().asInternalName())) {
                                instantiated = i.className();
                                skipDup = true;
                            } else {
                                builder.with(element);
                            }
                        }
                        case InvokeInstruction i -> {
                            if (i.opcode() == INVOKESPECIAL
                                    && REDIRECT_CONSTRUCTORS_OF.contains(i.method().owner().asInternalName())
                                    && i.method().name().equalsString("<init>")) {
                                if (!REDIRECT_CONSTRUCTORS_OF.contains(instantiated.asInternalName())) {
                                    System.err.println(format("Class '%s' cannot be instrumented as it extends '%s'",
                                                              instantiated.asInternalName(),
                                                              i.method().owner().asInternalName()));
                                    builder.with(element);
                                    return;
                                }
                                // Replace this instantiation of a non-deterministic implementation by a static
                                // invocation to a factory class providing a deterministic alternative.
                                var sourceClassDesc = i.method().owner().asInternalName();
                                var methodName =
                                        "new" + sourceClassDesc.substring(sourceClassDesc.lastIndexOf('/') + 1);
                                var methodDesc = MethodTypeDesc.of(i.method().owner().asSymbol(),
                                                                   i.typeSymbol().parameterList());
                                builder.invokestatic(targetClassDesc, methodName, methodDesc);
                            } else {
                                builder.with(element);
                            }
                        }
                        case StackInstruction s -> {
                            if (s.opcode() == DUP && skipDup) {
                                skipDup = false;
                            } else {
                                builder.with(element);
                            }
                        }
                        default -> builder.with(element);
                    }
                }
            }));
        }

    }

    private SimulatorAgent() {
        // Prevent instantiation
    }
}

