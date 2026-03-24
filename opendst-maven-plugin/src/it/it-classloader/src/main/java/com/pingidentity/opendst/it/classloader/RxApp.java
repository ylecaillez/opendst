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
package com.pingidentity.opendst.it.classloader;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Exercises RxJava's {@code Schedulers.io()} which internally uses {@code RxThreadFactory}.
 *
 * <p>{@code RxThreadFactory.newThread(Runnable)} has a conditional branch: one path creates
 * an {@code RxCustomThread} (a {@code Thread} subclass), the other creates a plain
 * {@code Thread}. When the call-site transform rewrites the plain {@code new Thread(...)}
 * to {@code INVOKESTATIC ThreadsInterceptors.newThread(...)}, the stack map frame at the
 * join point must merge {@code RxCustomThread} and {@code Thread} — which requires the
 * {@code ClassHierarchyResolver} to know that {@code RxCustomThread extends Thread}.
 *
 * <p>If the resolver cannot load {@code RxCustomThread} (because the dependency JAR is
 * missing from the classloader), it falls back to {@code Object}, producing an incorrect
 * merge type and a {@code VerifyError} at runtime.
 */
public final class RxApp {
    public static void main(String[] args) {
        // This triggers RxThreadFactory.newThread() via the io() scheduler's thread pool.
        var result = Observable.just(42)
                .subscribeOn(Schedulers.io())
                .map(v -> v * 2)
                .blockingFirst();
        System.out.println("RxJava result: " + result);
    }
}
