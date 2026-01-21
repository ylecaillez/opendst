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
package com.pingidentity.opendst.testapp;

import static java.nio.file.Files.createTempFile;

import java.io.FileOutputStream;
import java.io.IOException;

import com.pingidentity.opendst.api.Assert;
import com.pingidentity.opendst.api.Signals;

/**
 * A DST scenario used to verify that file system operations are correctly
 * intercepted and that fault injection can be triggered.
 */
public class FileSystemFaultInjectionDST {
    public void run() throws IOException {
        Signals.ready();
        try (var os = new FileOutputStream(createTempFile("opendst", "test").toFile())) {
            Assert.reachable("filesystem-op-success", null);
            os.write("OpenDST".getBytes());
        }
    }
}
