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
import static java.lang.classfile.Opcode.DUP;
import static java.lang.classfile.Opcode.INVOKESPECIAL;
import static java.lang.classfile.Opcode.INVOKESTATIC;
import static java.util.Map.entry;

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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Call-site bytecode transformation that redirects non-deterministic JDK APIs
 * to their deterministic simulator equivalents.
 *
 * <p>This class is shared between the agent (runtime transformation) and the
 * Maven plugin (offline/build-time transformation).
 */
public final class CallSiteTransform {
    private static final ClassDesc THREADS_CLASS = ClassDesc.of("com.pingidentity.opendst.ThreadsInterceptors");
    private static final ClassDesc SIGNALS_IMPL_CLASS = ClassDesc.of("com.pingidentity.opendst.SignalsImpl");
    private static final ClassDesc ASSERT_IMPL_CLASS = ClassDesc.of("com.pingidentity.opendst.AssertImpl");

    /** Maps internal class names to their deterministic redirect targets. */
    public static final Map<String, ClassDesc> REDIRECT_MAP = Map.ofEntries(
            entry("java/lang/Thread", THREADS_CLASS),
            entry("com/pingidentity/opendst/sdk/Signals", SIGNALS_IMPL_CLASS),
            entry("com/pingidentity/opendst/sdk/Assert", ASSERT_IMPL_CLASS));

    /** Classes whose constructor calls are redirected to static factory methods. */
    public static final Set<String> REDIRECT_CONSTRUCTORS_OF = Set.of("java/lang/Thread");

    /** Classes whose static method calls are redirected. */
    public static final Set<String> REDIRECT_STATIC_METHODS_OF =
            Set.of("com/pingidentity/opendst/sdk/Signals", "com/pingidentity/opendst/sdk/Assert");

    /** Returns a {@link ClassTransform} that rewrites call sites for deterministic simulation. */
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
                        System.err.printf(
                                "Class '%s' cannot be instrumented as it extends '%s' or has non-standard bytecode%n",
                                className, owner);
                    } else {
                        System.err.printf(
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

    private CallSiteTransform() {
        // Prevent instantiation
    }
}
