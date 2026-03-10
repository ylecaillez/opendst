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
import static java.nio.file.Files.exists;
import static java.nio.file.Files.list;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.jar.JarFile;

/**
 * Minimal bootstrap entry point for the self-contained JAR.
 *
 * <p>This class has <em>no</em> Jackson, SnakeYAML, or opendst-agent imports so that it can
 * be loaded by {@code java -jar} without those libraries on the classpath. It:
 * <ol>
 *   <li>Resolves the working directory (from {@code --working-dir} or derived from JAR path)</li>
 *   <li>Extracts the JAR contents into {@code <workingDir>/deployment/} (skipped if already present)</li>
 *   <li>Builds a {@link URLClassLoader} from every {@code deployment/system/*.jar} plus the deployment root</li>
 *   <li>Reflectively invokes {@link BuildRunner#main(String[])}</li>
 * </ol>
 *
 * <p>The working directory path is prepended to the argument array so that
 * {@code BuildRunner} knows where to find configuration and write output.
 */
public final class Bootstrap {
    public static void main(String[] args) {
        try {
            // 1. Locate the JAR we are running from
            var jarPath = Path.of(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            // 2. Resolve working directory and strip --working-dir from args before forwarding
            var workingDir = resolveWorkingDir(args, jarPath);
            args = stripWorkingDirArg(args);

            // 3. Extract into <workingDir>/deployment/ (skip if already present)
            var deploymentDir = workingDir.resolve("deployment");
            if (exists(deploymentDir.resolve("system"))) {
                err.println("Using existing deployment at: " + deploymentDir);
            } else {
                err.println("Extracting to: " + deploymentDir);
                extractJar(jarPath, deploymentDir);
            }

            // 4. Build a URLClassLoader with deployment/system/*.jar + deployment root
            var urls = new ArrayList<URL>();
            urls.add(deploymentDir.toUri().toURL()); // deployment root for plugin + opendst-agent classes
            var systemDir = deploymentDir.resolve("system");
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

                // Prepend workingDir as the first argument
                var newArgs = new String[args.length + 1];
                newArgs[0] = workingDir.toAbsolutePath().toString();
                System.arraycopy(args, 0, newArgs, 1, args.length);

                mainMethod.invoke(null, (Object) newArgs);
            }
        } catch (Exception e) {
            err.println("OpenDST bootstrap failed:");
            e.printStackTrace(err);
            exit(1);
        }
    }

    /**
     * Resolves the working directory from {@code --working-dir} argument or derives it from the JAR
     * path by stripping the {@code .jar} extension (e.g. {@code myapp-opendst.jar} → {@code myapp-opendst/}).
     */
    private static Path resolveWorkingDir(String[] args, Path jarPath) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--working-dir".equals(args[i])) {
                return Path.of(args[i + 1]).toAbsolutePath();
            }
        }
        // Default: JAR path minus .jar extension
        var jarName = jarPath.getFileName().toString();
        var dirName = jarName.endsWith(".jar") ? jarName.substring(0, jarName.length() - 4) : jarName + "-work";
        return jarPath.toAbsolutePath().getParent().resolve(dirName);
    }

    /** Returns args with {@code --working-dir <value>} stripped out (Bootstrap handles it, not picocli). */
    private static String[] stripWorkingDirArg(String[] args) {
        var result = new ArrayList<>(Arrays.asList(args));
        for (int i = 0; i < result.size() - 1; i++) {
            if ("--working-dir".equals(result.get(i))) {
                result.remove(i); // remove --working-dir
                result.remove(i); // remove value
                break;
            }
        }
        return result.toArray(String[]::new);
    }

    private static ClassLoader getPlatformClassLoader() {
        return ClassLoader.getPlatformClassLoader();
    }

    private static void extractJar(Path jarPath, Path targetDir) throws IOException {
        try (var jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var target = targetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(targetDir.normalize())) {
                    throw new IOException("Zip-Slip: entry '" + entry.getName()
                            + "' resolves outside target directory");
                }
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
}
