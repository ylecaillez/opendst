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
package com.pingidentity.opendst.maven;

import static com.pingidentity.opendst.common.AssertType.ALWAYS;
import static com.pingidentity.opendst.common.AssertType.SOMETIMES;

import com.pingidentity.opendst.common.Assertion;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scans class bytecode for OpenDST assertion calls and builds a catalog of discovered properties.
 *
 * <p>OpenDST requires a complete, static catalog of all reachable properties before any simulation
 * begins. This allows the orchestrator to track coverage and generate heatmaps accurately.
 * To ensure this catalog is static, this class enforces that the {@code message} argument of
 * every {@link com.pingidentity.opendst.sdk.Assert} call is a <b>string literal</b>.
 *
 * <p>The discovery works by performing lightweight symbolic stack tracking per method to resolve
 * the string literal argument at each Assert call site.
 */
final class PropertyDiscoverer {

    private static final String ASSERT_OWNER = "com/pingidentity/opendst/sdk/Assert";

    private PropertyDiscoverer() {}

    /**
     * Scans a class model for OpenDST assertions and adds discovered properties to the given set.
     *
     * @throws Instrumentation.AssertionValidationException if an assertion uses a non-literal label
     */
    static void discover(ClassModel model, Set<Assertion> discovered) {
        try {
            var className = model.thisClass().asInternalName().replace('/', '.');
            for (var method : model.methods()) {
                var methodName = method.methodName().toString();
                for (var code : method.code().stream().toList()) {
                    scanMethod(code, className, methodName, discovered);
                }
            }
        } catch (Instrumentation.AssertionValidationException ave) {
            throw ave;
        } catch (Exception e) {
            // Best effort discovery for application code. Don't crash on complex third-party bytecode.
        }
    }

    private static void scanMethod(
            Iterable<? extends java.lang.classfile.CodeElement> code,
            String className,
            String methodName,
            Set<Assertion> discovered) {
        // Symbolic stack tracking must be sequential per method to be correct.
        var stack = new ArrayList<String>();
        int currentLine = -1;
        for (var element : code) {
            switch (element) {
                case LineNumber ln -> currentLine = ln.line();
                case ConstantInstruction ci -> {
                    var val = ci.constantValue();
                    stack.add(val instanceof String s ? s : "");
                }
                case InvokeInstruction inv -> {
                    if (inv.method().owner().asInternalName().equals(ASSERT_OWNER)) {
                        handleAssertCall(inv, stack, className, methodName, currentLine, discovered);
                    }
                    updateStackForInvoke(inv, stack);
                }
                case LoadInstruction li -> stack.add(null);
                case FieldInstruction fi -> updateStackForField(fi, stack);
                case OperatorInstruction oi -> {
                    int pop = getOperatorPopCount(oi.opcode());
                    for (int k = 0; k < pop && !stack.isEmpty(); k++) {
                        stack.removeLast();
                    }
                    stack.add(null);
                }
                case StackInstruction si -> handleStackInstruction(si, stack);
                case Instruction instr -> {
                    var opcode = instr.opcode();
                    if (opcode == Opcode.NEW) {
                        stack.add(null);
                    } else if (opcode == Opcode.INSTANCEOF || opcode == Opcode.ARRAYLENGTH) {
                        // Pop one, push one (net zero but replace top with non-string)
                        if (!stack.isEmpty()) {
                            stack.removeLast();
                        }
                        stack.add(null);
                    } else if (opcode.name().contains("ALOAD") || opcode.name().contains("ASTORE")) {
                        stack.clear(); // Conservatively clear on complex array operations
                    }
                }
                default -> {}
            }
        }
    }

    private static void handleAssertCall(
            InvokeInstruction inv,
            List<String> stack,
            String className,
            String methodName,
            int line,
            Set<Assertion> discovered) {
        var name = inv.method().name().toString();
        var type = inv.typeSymbol();
        var params = type.parameterList();
        int pos = 0;
        for (int i = params.size() - 1; i >= 0; i--) {
            pos += TypeKind.from(params.get(i)).slotSize();
            if (params.get(i).descriptorString().equals("Ljava/lang/String;")) {
                break;
            }
        }

        String message = null;
        if (stack.size() >= pos) {
            message = stack.get(stack.size() - pos);
        }

        if (message == null || message.isEmpty()) {
            throw new Instrumentation.AssertionValidationException(
                    "Invalid OpenDST assertion in %s.%s (line %d): message must be a string literal for Assert.%s%s"
                            .formatted(className, methodName, line, name, type.displayDescriptor()));
        }
        var kind = name.startsWith("sometimes") || name.equals("reachable") ? SOMETIMES : ALWAYS;
        discovered.add(new Assertion(kind, message, className, line));
    }

    private static void updateStackForInvoke(InvokeInstruction inv, List<String> stack) {
        int pop = getPopCount(inv.typeSymbol(), inv.opcode() != Opcode.INVOKESTATIC);
        for (int k = 0; k < pop && !stack.isEmpty(); k++) {
            stack.removeLast();
        }
        int push = TypeKind.from(inv.typeSymbol().returnType()).slotSize();
        for (int k = 0; k < push; k++) {
            stack.add(null);
        }
    }

    private static void updateStackForField(FieldInstruction fi, List<String> stack) {
        boolean isPut = fi.opcode() == Opcode.PUTFIELD || fi.opcode() == Opcode.PUTSTATIC;
        boolean isStatic = fi.opcode() == Opcode.GETSTATIC || fi.opcode() == Opcode.PUTSTATIC;
        int slots = TypeKind.from(fi.typeSymbol()).slotSize();
        if (isPut) {
            int pop = slots + (isStatic ? 0 : 1);
            for (int k = 0; k < pop && !stack.isEmpty(); k++) {
                stack.removeLast();
            }
        } else {
            if (!isStatic && !stack.isEmpty()) {
                stack.removeLast();
            }
            for (int k = 0; k < slots; k++) {
                stack.add(null);
            }
        }
    }

    /** Calculates how many stack slots an invocation pops. */
    private static int getPopCount(MethodTypeDesc type, boolean hasReceiver) {
        int count = hasReceiver ? 1 : 0;
        for (var arg : type.parameterList()) {
            count += TypeKind.from(arg).slotSize();
        }
        return count;
    }

    /** Returns the number of stack slots popped by an operator instruction. */
    private static int getOperatorPopCount(Opcode opcode) {
        return switch (opcode) {
            case INEG, LNEG, FNEG, DNEG, I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D, D2I, D2L, D2F, I2B, I2C, I2S -> 1;
            default -> 2;
        };
    }

    /** Simulates stack manipulation instructions (DUP, SWAP, etc.) on the symbolic stack. */
    private static void handleStackInstruction(StackInstruction si, List<String> stack) {
        if (stack.isEmpty()) {
            return;
        }
        switch (si.opcode()) {
            case POP -> stack.removeLast();
            case POP2 -> {
                stack.removeLast();
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            }
            case DUP -> stack.add(stack.getLast());
            case DUP_X1 -> {
                if (stack.size() < 2) {
                    return;
                }
                var top = stack.removeLast();
                var next = stack.removeLast();
                stack.add(top);
                stack.add(next);
                stack.add(top);
            }
            case DUP_X2 -> {
                if (stack.size() < 3) {
                    return;
                }
                var top = stack.removeLast();
                var n1 = stack.removeLast();
                var n2 = stack.removeLast();
                stack.add(top);
                stack.add(n2);
                stack.add(n1);
                stack.add(top);
            }
            case DUP2 -> {
                if (stack.size() < 2) {
                    return;
                }
                var v1 = stack.getLast();
                var v2 = stack.get(stack.size() - 2);
                stack.add(v2);
                stack.add(v1);
            }
            case SWAP -> {
                if (stack.size() < 2) {
                    return;
                }
                var v1 = stack.removeLast();
                var v2 = stack.removeLast();
                stack.add(v1);
                stack.add(v2);
            }
            default -> {}
        }
    }
}
