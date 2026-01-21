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
import static com.pingidentity.opendst.maven.Signal.AssertSignal.AssertType;
import static java.lang.classfile.ClassHierarchyResolver.ofClassLoading;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;
import static java.nio.file.Files.walkFileTree;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFile.ClassHierarchyResolverOption;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ClassDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.apache.maven.plugin.MojoFailureException;

/**
 * Orchestrates the offline instrumentation of application bytecode.
 *
 * <p>Transforms "Call-Sites" of non-deterministic JDK APIs (like {@code new Socket()})
 * to redirect them to the deterministic simulator. During transformation, each class is
 * also scanned for OpenDST assertions via {@link PropertyDiscoverer}.
 */
final class Instrumentation {
    private final Path basePath;
    private final Path warsDir;
    private final Path instrumentedWarsDir;
    private final ExecutorService executor;
    private final OpenDstLogger logger;

    /** Represents a property found in the bytecode. */
    record DiscoveredProperty(AssertType kind, String message, String origin, int line) {}

    /** Result of a class transformation task. */
    private record TransformationResult(String name, byte[] content) {}

    /** Thrown when an OpenDST assertion is found to be invalid (e.g. non-literal label). */
    @SuppressWarnings("serial")
    static final class AssertionValidationException extends RuntimeException {
        AssertionValidationException(String message) {
            super(message);
        }
    }

    Instrumentation(
            Path basePath, Path warsDir, Path instrumentedWarsDir, ExecutorService executor, OpenDstLogger logger) {
        this.basePath = basePath;
        this.warsDir = warsDir;
        this.instrumentedWarsDir = instrumentedWarsDir;
        this.executor = executor;
        this.logger = logger;
    }

    /**
     * Instruments all classes in the project's test-classes folder and any WARs
     * found in the configured wars directory.
     *
     * @return A set of all OpenDST properties discovered during the instrumentation.
     * @throws MojoFailureException if instrumentation or validation fails.
     */
    Set<DiscoveredProperty> instrumentWars() throws MojoFailureException {
        logger.raw().info("Instrumenting wars in %s".formatted(basePath.relativize(warsDir)));
        var discovered = ConcurrentHashMap.<DiscoveredProperty>newKeySet();
        try {
            createDirectories(instrumentedWarsDir);
            var urls = getClasspathToInstrument();
            try (var projectLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
                var classFile = ClassFile.of(ClassHierarchyResolverOption.of(ofClassLoading(projectLoader)
                        .orElse(desc -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(
                                ClassDesc.ofDescriptor("Ljava/lang/Object;")))));
                // 1. Instrument test classes (where DST scenarios usually live)
                instrumentClassesFolder(
                        classFile,
                        basePath.resolve("target/test-classes"),
                        instrumentedWarsDir.resolve("test-classes.jar"),
                        discovered);
                // 2. Instrument application WARs/JARs
                instrumentApplicationArtifacts(classFile, discovered);
            }
        } catch (AssertionValidationException e) {
            throw new MojoFailureException(e.getMessage());
        } catch (IOException e) {
            throw new MojoFailureException("Failed to instrument wars", e);
        }
        return discovered;
    }

    private void instrumentApplicationArtifacts(ClassFile classFile, Set<DiscoveredProperty> discovered)
            throws IOException {
        if (!exists(warsDir)) {
            return;
        }

        walkFileTree(warsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().equals("classes") && !dir.equals(warsDir)) {
                    var targetJar = instrumentedWarsDir.resolve(warsDir.relativize(dir) + ".jar");
                    instrumentClassesFolder(classFile, dir, targetJar, discovered);
                    return SKIP_SUBTREE;
                }
                return CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var targetFile = instrumentedWarsDir.resolve(warsDir.relativize(file));
                createDirectories(targetFile.getParent());

                if (file.getFileName().toString().endsWith(".jar")) {
                    instrumentJar(classFile, file, targetFile, discovered);
                } else {
                    copy(file, targetFile, REPLACE_EXISTING);
                }
                return CONTINUE;
            }
        });
    }

    private URL[] getClasspathToInstrument() throws IOException {
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
            try (var stream = walk(warsDir)) {
                var paths = stream.filter(path -> (isDirectory(path)
                                        && path.getFileName().toString().equals("classes"))
                                || path.getFileName().toString().endsWith(".jar"))
                        .toList();
                for (var path : paths) {
                    urls.add(path.toUri().toURL());
                }
            }
        }
        return urls.toArray(URL[]::new);
    }

    /** Instruments a directory of class files and bundles them into a JAR. */
    private void instrumentClassesFolder(
            ClassFile classFile, Path sourceDir, Path targetJar, Set<DiscoveredProperty> discovered)
            throws IOException {
        if (!exists(sourceDir)) {
            return;
        }
        try (var jos = new JarOutputStream(newOutputStream(targetJar));
                var stream = walk(sourceDir)) {
            instrumentEntries(classFile, jos, discovered, sourceDir, stream.filter(Files::isRegularFile));
        }
    }

    /** Instruments a JAR file and writes the result to a target location. */
    private void instrumentJar(ClassFile classFile, Path jarPath, Path targetJar, Set<DiscoveredProperty> discovered)
            throws IOException {
        try (var fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null);
                var jos = new JarOutputStream(newOutputStream(targetJar))) {
            var root = fs.getPath("/");
            try (var stream = walk(root)) {
                instrumentEntries(classFile, jos, discovered, root, stream.filter(Files::isRegularFile));
            }
        }
    }

    /** Core logic for instrumenting a stream of entries. */
    private void instrumentEntries(
            ClassFile classFile,
            JarOutputStream jos,
            Set<DiscoveredProperty> discovered,
            Path root,
            Stream<Path> entries)
            throws IOException {
        var completionService = new ExecutorCompletionService<TransformationResult>(executor);
        int classTasks = 0;

        for (var it = entries.iterator(); it.hasNext(); ) {
            var path = it.next();
            // Normalize the entry name:
            // 1. Relativize against the root to get the path within the archive/folder.
            // 2. Remove leading slash (some FileSystems like ZipFileSystem return them).
            // 3. Force forward slashes for ZIP/JAR compatibility (especially on Windows).
            var name = root.relativize(path).toString();
            if (name.startsWith("/")) {
                name = name.substring(1);
            }
            name = name.replace('\\', '/');
            if (name.endsWith(".class")) {
                classTasks++;
                final var entryName = name;
                completionService.submit(() -> {
                    var model = classFile.parse(readAllBytes(path));
                    PropertyDiscoverer.discover(model, discovered);
                    return new TransformationResult(
                            entryName, classFile.transformClass(model, callSiteTransformMethod()));
                });
            } else if (!name.isEmpty()) {
                var newEntry = new JarEntry(name);
                jos.putNextEntry(newEntry);
                jos.write(readAllBytes(path));
                jos.closeEntry();
            }
        }

        for (int i = 0; i < classTasks; i++) {
            try {
                var result = completionService.take().get();
                jos.putNextEntry(new JarEntry(result.name()));
                jos.write(result.content());
                jos.closeEntry();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while transforming entries in %s".formatted(root), e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AssertionValidationException ave) {
                    throw ave;
                }
                throw new IOException("Failed to transform class in %s".formatted(root), e);
            }
        }
    }
}
