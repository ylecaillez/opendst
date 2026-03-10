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

import static com.pingidentity.opendst.Simulator.REDIRECT_CONSTRUCTORS_OF;
import static com.pingidentity.opendst.Simulator.REDIRECT_STATIC_METHODS_OF;
import static java.lang.System.err;
import static java.lang.System.setProperty;
import static java.lang.classfile.ClassTransform.transformingMethods;
import static java.lang.classfile.Opcode.DUP;
import static java.lang.classfile.Opcode.INVOKESPECIAL;
import static java.lang.classfile.Opcode.INVOKESTATIC;
import static java.util.Map.entry;
import static net.bytebuddy.matcher.ElementMatchers.none;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Default;
import net.bytebuddy.agent.builder.AgentBuilder.InitializationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.InjectionStrategy.UsingReflection;
import net.bytebuddy.agent.builder.AgentBuilder.Listener.StreamWriting;
import net.bytebuddy.agent.builder.AgentBuilder.RedefinitionStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.TypeStrategy;
import net.bytebuddy.implementation.Implementation.Context.Disabled.Factory;

/**
 * Intercepts key JDK methods to provide a deterministic execution environment.
 */
public final class SimulatorAgent {
    public static final String AGENT_PROPERTY = "com.pingidentity.opendst.simulator.agent";
    private static final ClassDesc THREADS_CLASS = ClassDesc.of("com.pingidentity.opendst.Threads");
    private static final ClassDesc SIGNALS_IMPL_CLASS = ClassDesc.of("com.pingidentity.opendst.SignalsImpl");
    private static final ClassDesc ASSERT_IMPL_CLASS = ClassDesc.of("com.pingidentity.opendst.AssertImpl");

    static final Map<String, ClassDesc> REDIRECT_MAP = Map.ofEntries(
            entry("java/lang/Thread", THREADS_CLASS),
            entry("com/pingidentity/opendst/api/Signals", SIGNALS_IMPL_CLASS),
            entry("com/pingidentity/opendst/api/Assert", ASSERT_IMPL_CLASS));

    /**
     * Installs bytecode transformation.
     *
     * @param agentArgs       Arguments for the agent, not used here.
     * @param instrumentation The instrumentation instance to use for transforming classes.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        AgentBuilder agent = new Default()
                .disableClassFormatChanges()
                .enableNativeMethodPrefix("native")
                .with(new ByteBuddy().with(Factory.INSTANCE))
                .with(InitializationStrategy.NoOp.INSTANCE)
                .with(UsingReflection.INSTANCE)
                .with(RedefinitionStrategy.REDEFINITION)
                .with(TypeStrategy.Default.REDEFINE)
                .with(StreamWriting.toSystemError().withErrorsOnly())
                .ignore(none());

        agent = Time.instrument(agent);
        agent = SystemInterceptors.instrument(agent);
        agent = Randomness.instrument(agent);
        agent = Threads.instrument(agent);
        agent = Network.instrument(agent);

        agent.installOn(instrumentation);

        instrumentation.addTransformer(new CallSiteInstrumentation());
        setProperty(AGENT_PROPERTY, "true");
    }

    private static final class CallSiteInstrumentation implements ClassFileTransformer {
        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {
            var classFile = ClassFile.of();
            // Instrument the JDK ThreadBuilders to return VirtualThread rather than Thread
            return className != null && className.startsWith("java/lang/ThreadBuilders")
                    ? classFile.transformClass(classFile.parse(classfileBuffer), callSiteTransformMethod())
                    : null;
        }
    }

    public static ClassTransform callSiteTransformMethod() {
        return transformingMethods((methodBuilder, methodElement) -> {
            if (methodElement instanceof CodeModel codeModel) {
                var className =
                        codeModel.parent().get().parent().get().thisClass().asInternalName();
                methodBuilder.transformCode(codeModel, new CallSiteTransformer(className));
            } else {
                methodBuilder.with(methodElement);
            }
        });
    }

    /**
     * Replaces instantiation of non-deterministic classes with static factory calls.
     * <p>
     * Uses a stack-based approach to robustly handle nested allocations and DUP instructions.
     */
    private static final class CallSiteTransformer implements CodeTransform {
        private final String className;
        private final Deque<ClassEntry> instantiatedStack = new ArrayDeque<>();
        private int skipDupCount = 0;

        private CallSiteTransformer(String className) {
            this.className = Objects.requireNonNull(className);
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            switch (element) {
                case NewObjectInstruction i -> handleNew(builder, i);
                case InvokeInstruction i -> handleInvoke(builder, i);
                case StackInstruction s -> handleStack(builder, s);
                default -> builder.with(element);
            }
        }

        private void handleNew(CodeBuilder builder, NewObjectInstruction i) {
            if (REDIRECT_CONSTRUCTORS_OF.contains(i.className().asInternalName())) {
                instantiatedStack.push(i.className());
                skipDupCount++;
            } else {
                builder.with(i);
            }
        }

        private void handleInvoke(CodeBuilder builder, InvokeInstruction i) {
            var owner = i.method().owner().asInternalName();
            if (i.opcode() == INVOKESPECIAL
                    && REDIRECT_CONSTRUCTORS_OF.contains(owner)
                    && i.method().name().equalsString("<init>")) {

                if (!instantiatedStack.isEmpty()
                        && instantiatedStack.peek().asInternalName().equals(owner)) {
                    instantiatedStack.pop();
                    // Replace this instantiation of a non-deterministic implementation by a static
                    // invocation to a factory class providing a deterministic alternative.
                    var targetClass = Objects.requireNonNull(REDIRECT_MAP.get(owner));
                    var methodName = "new" + owner.substring(owner.lastIndexOf('/') + 1);
                    var methodDesc = MethodTypeDesc.of(
                            i.method().owner().asSymbol(), i.typeSymbol().parameterList());
                    builder.invokestatic(targetClass, methodName, methodDesc);
                } else {
                    // This can happen if a class extends a redirected class and calls super()
                    // or if the bytecode doesn't follow the standard NEW/DUP pattern.
                    if (instantiatedStack.isEmpty()) {
                        err.printf(
                                "Class '%s' cannot be instrumented as it extends '%s' or has non-standard bytecode%n",
                                className, owner);
                    } else {
                        err.printf(
                                "Class '%s' instrumentation error: expected %s on stack but found %s%n",
                                className, owner, instantiatedStack.peek().asInternalName());
                    }
                    builder.with(i);
                }
            } else if (i.opcode() == INVOKESTATIC && REDIRECT_STATIC_METHODS_OF.contains(owner)) {
                var targetClass = Objects.requireNonNull(REDIRECT_MAP.get(owner));
                var methodName = i.method().name().toString();
                var methodDesc = i.typeSymbol();
                builder.invokestatic(targetClass, methodName, methodDesc);
            } else {
                builder.with(i);
            }
        }

        private void handleStack(CodeBuilder builder, StackInstruction s) {
            if (s.opcode() == DUP && skipDupCount > 0) {
                skipDupCount--;
            } else {
                builder.with(s);
            }
        }
    }

    private SimulatorAgent() {
        // Prevent instantiation
    }
}
