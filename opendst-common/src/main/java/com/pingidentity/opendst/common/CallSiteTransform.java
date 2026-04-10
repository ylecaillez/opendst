/*
 * Copyright 2026 Ping Identity Corporation
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
package com.pingidentity.opendst.common;

import static java.lang.classfile.ClassTransform.transformingMethods;
import static java.lang.classfile.Opcode.INVOKESPECIAL;
import static java.lang.classfile.Opcode.INVOKESTATIC;
import static java.util.Map.entry;

import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Superclass;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.util.Map;
import java.util.Objects;

/**
 * Call-site bytecode transformation that redirects non-deterministic JDK APIs
 * to their deterministic simulator equivalents.
 *
 * <p>This class is shared between the agent (runtime transformation) and the
 * Maven plugin (offline/build-time transformation).
 */
public final class CallSiteTransform {
    private static final ClassDesc SIMULATOR_THREAD_CLASS = ClassDesc.of("java.lang.SimulatorThread");
    private static final ClassDesc SIGNALS_IMPL_CLASS = ClassDesc.of("com.pingidentity.opendst.SignalsImpl");
    private static final ClassDesc ASSERT_IMPL_CLASS = ClassDesc.of("com.pingidentity.opendst.AssertImpl");

    private static final String THREAD_INTERNAL = "java/lang/Thread";

    /** Maps internal class names of static method sources to their deterministic redirect targets. */
    public static final Map<String, ClassDesc> REDIRECT_STATIC_METHODS = Map.ofEntries(
            entry("com/pingidentity/opendst/sdk/Signals", SIGNALS_IMPL_CLASS),
            entry("com/pingidentity/opendst/sdk/Assert", ASSERT_IMPL_CLASS));

    /** Returns a {@link ClassTransform} that rewrites call sites for deterministic simulation. */
    public static ClassTransform callSiteTransformMethod() {
        return transformingMethods((methodBuilder, methodElement) -> {
            if (methodElement instanceof CodeModel codeModel) {
                methodBuilder.transformCode(codeModel, new CallSiteTransformer());
            } else {
                methodBuilder.with(methodElement);
            }
        });
    }

    /**
     * Returns a {@link ClassTransform} that rewrites direct {@code Thread} subclasses to
     * extend {@code SimulatorThread} instead, so they run as virtual threads under simulation.
     *
     * <p>This transform changes the superclass from {@code java/lang/Thread} to
     * {@code java/lang/SimulatorThread}. Constructor {@code super()} calls are rewritten by
     * {@link CallSiteTransformer} which handles all {@code INVOKESPECIAL Thread.<init>} rewrites.
     *
     * <p>Only <em>direct</em> {@code Thread} subclasses are rewritten. Transitive subclasses
     * (e.g. {@code MyThread extends ZooKeeperThread extends Thread}) are handled automatically
     * because their parent ({@code ZooKeeperThread}) has already been rewritten.
     */
    public static ClassTransform threadSubclassTransform() {
        return (classBuilder, classElement) -> {
            if (classElement instanceof Superclass sc
                    && THREAD_INTERNAL.equals(sc.superclassEntry().asInternalName())) {
                classBuilder.withSuperclass(SIMULATOR_THREAD_CLASS);
            } else {
                classBuilder.with(classElement);
            }
        };
    }

    /**
     * Returns {@code true} if the given superclass internal name is {@code java/lang/Thread},
     * meaning the class is a direct Thread subclass that should be rewritten.
     */
    public static boolean isDirectThreadSubclass(String superclassInternalName) {
        return THREAD_INTERNAL.equals(superclassInternalName);
    }

    /**
     * Rewrites call sites for deterministic simulation:
     * <ul>
     *   <li>{@code NEW java/lang/Thread} → {@code NEW java/lang/SimulatorThread}</li>
     *   <li>{@code INVOKESPECIAL Thread.<init>} → {@code INVOKESPECIAL SimulatorThread.<init>}</li>
     *   <li>{@code INVOKESTATIC Signals/Assert.method()} → {@code INVOKESTATIC SignalsImpl/AssertImpl.method()}</li>
     * </ul>
     */
    private static final class CallSiteTransformer implements CodeTransform {
        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            switch (element) {
                case NewObjectInstruction i
                when THREAD_INTERNAL.equals(i.className().asInternalName()) -> builder.new_(SIMULATOR_THREAD_CLASS);
                case InvokeInstruction i -> handleInvoke(builder, i);
                default -> builder.with(element);
            }
        }

        private void handleInvoke(CodeBuilder builder, InvokeInstruction i) {
            var owner = i.method().owner().asInternalName();
            if (i.opcode() == INVOKESPECIAL
                    && THREAD_INTERNAL.equals(owner)
                    && i.method().name().equalsString("<init>")) {
                // Rewrite Thread.<init> → SimulatorThread.<init> (same descriptor).
                // Covers both: new Thread(runnable) and super() calls in Thread subclasses.
                builder.invokespecial(SIMULATOR_THREAD_CLASS, "<init>", i.typeSymbol());
            } else if (i.opcode() == INVOKESTATIC && REDIRECT_STATIC_METHODS.containsKey(owner)) {
                var targetClass = Objects.requireNonNull(REDIRECT_STATIC_METHODS.get(owner));
                builder.invokestatic(targetClass, i.method().name().toString(), i.typeSymbol());
            } else {
                builder.with(i);
            }
        }
    }

    private CallSiteTransform() {
        // Prevent instantiation
    }
}
