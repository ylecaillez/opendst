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
package com.pingidentity.opendst.runner;

import static java.lang.System.err;
import static java.lang.System.exit;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.list;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.jar.JarFile;

/**
 * Minimal bootstrap entry point for the self-contained JAR.
 *
     * <p>This class has <em>no</em> Jackson, SnakeYAML, or opendst-agent imports so that it can
 * be loaded by {@code java -jar} without those libraries on the classpath. It:
 * <ol>
 *   <li>Extracts the JAR contents to a temporary directory</li>
 *   <li>Builds a {@link URLClassLoader} from every {@code system/*.jar} plus the extraction root</li>
 *   <li>Reflectively invokes {@link BuildRunner#main(String[])}</li>
 * </ol>
 *
 * <p>The extraction directory path is prepended to the argument array so that
 * {@code BuildRunner} knows where to find the extracted files.
 */
public final class Bootstrap {
    public static void main(String[] args) {
        try {
            // 1. Locate the JAR we are running from
            var jarPath = Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().getPath());

            // 2. Extract to temp directory
            var extractDir = createTempDirectory("opendst-build-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(extractDir)));
            extractJar(jarPath, extractDir);

            // 3. Build a URLClassLoader with system/*.jar + extraction root
            var urls = new ArrayList<URL>();
            urls.add(extractDir.toUri().toURL()); // extraction root for plugin + opendst-agent classes
            var systemDir = extractDir.resolve("system");
            try (var jars = list(systemDir)) {
                jars.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(p -> {
                            try {
                                urls.add(p.toUri().toURL());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }

            // Parent is platform classloader — isolates from system CL (which only sees the bootstrap JAR)
            try (var cl = new URLClassLoader(urls.toArray(URL[]::new), getPlatformClassLoader())) {
                Thread.currentThread().setContextClassLoader(cl);
                Class<?> runnerClass = cl.loadClass("com.pingidentity.opendst.runner.BuildRunner");
                Method mainMethod = runnerClass.getMethod("main", String[].class);

                // Prepend extractDir as the first argument
                var newArgs = new String[args.length + 1];
                newArgs[0] = extractDir.toAbsolutePath().toString();
                System.arraycopy(args, 0, newArgs, 1, args.length);

                mainMethod.invoke(null, (Object) newArgs);
            }
        } catch (Exception e) {
            err.println("OpenDST bootstrap failed:");
            e.printStackTrace(err);
            exit(1);
        }
    }

    private static ClassLoader getPlatformClassLoader() {
        return ClassLoader.getPlatformClassLoader();
    }

    private static void extractJar(Path jarPath, Path targetDir) throws IOException {
        try (var jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var target = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    createDirectories(target);
                } else {
                    createDirectories(target.getParent());
                    try (InputStream is = jar.getInputStream(entry)) {
                        copy(is, target);
                    }
                }
            }
        }
    }

    @SuppressWarnings("java:S899")
    private static void deleteQuietly(Path dir) {
        try {
            // Walk in reverse order so files are deleted before their parent directories
            try (var stream = java.nio.file.Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                java.nio.file.Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException ignored) {
        }
    }
}
