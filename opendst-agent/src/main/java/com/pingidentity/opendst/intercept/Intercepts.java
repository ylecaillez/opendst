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
package com.pingidentity.opendst.intercept;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a class or method provides advice for a specific intercepted method.
 * Used for automated auditing of OpenDST instrumentation.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Intercepts.List.class)
public @interface Intercepts {
    /**
     * {@return the fully qualified name of the class or method being intercepted
     * (e.g. "java.lang.System#nanoTime()")}
     */
    String value();

    /**
     * {@return whether this is a "no-op" (no operation) implementation.}
     */
    boolean noOp() default false;

    /**
     * {@return an optional description of the rationale for this interception,
     * or what behavior still needs to be implemented if it is a no-op.}
     */
    String comment() default "";

    /** Container for repeatable {@link Intercepts} annotations. */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        Intercepts[] value();
    }
}
