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

import static com.pingidentity.opendst.SimulatorAgent.callSiteTransformMethod;
import static java.lang.classfile.ClassHierarchyResolver.ofClassLoading;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walkFileTree;
import static java.nio.file.Files.write;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFile.ClassHierarchyResolverOption;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;

final class Instrumentation {
    private final Path basePath;
    private final Path warsDir;
    private final Path instrumentedWarsDir;
    private final Log log;

    Instrumentation(Path basePath, Path warsDir, Path instrumentedWarsDir, Log log) {
        this.basePath = basePath;
        this.warsDir = warsDir;
        this.instrumentedWarsDir = instrumentedWarsDir;
        this.log = log;
    }

    void instrumentWars() throws MojoFailureException {
        try {
            createDirectories(instrumentedWarsDir);
            var urls = getClasspathToInstrument();
            try (var projectLoader =
                    new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader())) {
                var classFile = ClassFile.of(ClassHierarchyResolverOption.of(ofClassLoading(projectLoader)));
                instrumentClassesFolder(
                        classFile,
                        basePath.resolve("target/test-classes"),
                        instrumentedWarsDir.resolve("test-classes.jar"));
                if (exists(warsDir)) {
                    walkFileTree(warsDir, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                throws IOException {
                            if (dir.getFileName().toString().equals("classes")) {
                                var targetJar = instrumentedWarsDir.resolve(
                                        warsDir.relativize(dir).toString() + ".jar");
                                instrumentClassesFolder(classFile, dir, targetJar);
                                return SKIP_SUBTREE;
                            }
                            var targetDir = instrumentedWarsDir.resolve(warsDir.relativize(dir));
                            createDirectories(targetDir);
                            return CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            var targetFile = instrumentedWarsDir.resolve(warsDir.relativize(file));
                            var fileName = file.getFileName().toString();
                            if (fileName.endsWith(".class")) {
                                var content = readAllBytes(file);
                                var classFile =
                                        ClassFile.of(ClassHierarchyResolverOption.of(ofClassLoading(projectLoader)));
                                var transformed = instrumentClass(classFile, content);
                                write(targetFile, transformed != null ? transformed : content);
                            } else if (fileName.endsWith(".jar")) {
                                instrumentJar(classFile, file, targetFile);
                            } else {
                                copy(file, targetFile, REPLACE_EXISTING);
                            }
                            return CONTINUE;
                        }
                    });
                }
            }
        } catch (IOException e) {
            throw new MojoFailureException("Failed to instrument wars", e);
        }
    }

    private List<URL> getClasspathToInstrument() throws IOException {
        var urls = new ArrayList<URL>();
        var mainClassesDir = basePath.resolve("target/classes");
        if (exists(mainClassesDir)) {
            urls.add(mainClassesDir.toUri().toURL());
        }
        var testClassesDir = basePath.resolve("target/test-classes");
        if (exists(testClassesDir)) {
            urls.add(testClassesDir.toUri().toURL());
        }
        if (exists(warsDir)) {
            walkFileTree(warsDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.getFileName().toString().equals("classes")) {
                        urls.add(dir.toUri().toURL());
                    }
                    return CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().endsWith(".jar")) {
                        urls.add(file.toUri().toURL());
                    }
                    return CONTINUE;
                }
            });
        }
        return urls;
    }

    private void instrumentClassesFolder(ClassFile classFile, Path sourceDir, Path targetJar) throws IOException {
        try (var jos = new JarOutputStream(newOutputStream(targetJar))) {
            walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    var entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                    var newEntry = new JarEntry(entryName);
                    jos.putNextEntry(newEntry);
                    var content = readAllBytes(file);
                    if (entryName.endsWith(".class")) {
                        var transformed = instrumentClass(classFile, content);
                        jos.write(transformed != null ? transformed : content);
                    } else {
                        jos.write(content);
                    }
                    jos.closeEntry();
                    return CONTINUE;
                }
            });
        }
    }

    private void instrumentJar(ClassFile classFile, Path sourceJar, Path targetJar) throws IOException {
        log.debug("Instrumenting %s".formatted(basePath.relativize(sourceJar)));
        try (var jarFile = new JarFile(sourceJar.toFile());
                var jos = new JarOutputStream(newOutputStream(targetJar))) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var newEntry = new JarEntry(entry.getName());
                jos.putNextEntry(newEntry);
                try (var is = jarFile.getInputStream(entry)) {
                    var content = is.readAllBytes();
                    if (entry.getName().endsWith(".class")) {
                        var transformed = instrumentClass(classFile, content);
                        jos.write(transformed != null ? transformed : content);
                    } else {
                        jos.write(content);
                    }
                }
                jos.closeEntry();
            }
        }
    }

    private byte[] instrumentClass(ClassFile classFile, byte[] content) {
        return classFile.transformClass(classFile.parse(content), callSiteTransformMethod());
    }
}
