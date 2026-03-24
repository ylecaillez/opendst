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
package com.pingidentity.opendst.it.instrumentation;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Test-scoped counterpart of {@link RxApp} — exercises the same RxJava code path
 * but lives in {@code src/test/java/} to verify {@code scope: test} instrumentation.
 *
 * <p>When the deployment descriptor declares {@code scope: test}, the plugin instruments
 * both {@code target/classes/} and {@code target/test-classes/} into a single
 * {@code classes.jar}. This class proves that test classes are correctly included
 * and that the {@code ClassHierarchyResolver} can still resolve {@code RxCustomThread}
 * (a {@code Thread} subclass from the RxJava dependency JAR).
 */
public final class RxTestApp {
    public static void main(String[] args) {
        var result = Observable.just(99)
                .subscribeOn(Schedulers.io())
                .map(v -> v + 1)
                .blockingFirst();
        System.out.println("RxJava test result: " + result);
    }
}
