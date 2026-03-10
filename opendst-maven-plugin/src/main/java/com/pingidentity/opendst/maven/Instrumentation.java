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
import static com.pingidentity.opendst.runner.Signal.AssertSignal.AssertType;
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

import com.pingidentity.opendst.runner.Assertion;
import com.pingidentity.opendst.runner.OpenDstLogger;

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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Orchestrates the offline instrumentation of application bytecode.
 *
 * <p>Transforms "Call-Sites" of non-deterministic JDK APIs (like {@code new Socket()})
 * to redirect them to the deterministic simulator. During transformation, each class is
 * also scanned for OpenDST assertions via {@link PropertyDiscoverer}.
 */
final class Instrumentation {
    private final Path basePath;
    private final Path instrumentedWarsDir;
    private final ExecutorService executor;
    private final OpenDstLogger logger;

    /** Result of a class transformation task. */
    private record TransformationResult(String name, byte[] content) {}

    /** Thrown when an OpenDST assertion is found to be invalid (e.g. non-literal label). */
    @SuppressWarnings("serial")
    static final class AssertionValidationException extends RuntimeException {
        AssertionValidationException(String message) {
            super(message);
        }
    }

    Instrumentation(Path basePath, Path instrumentedWarsDir, ExecutorService executor, OpenDstLogger logger) {
        this.basePath = basePath;
        this.instrumentedWarsDir = instrumentedWarsDir;
        this.executor = executor;
        this.logger = logger;
    }

    /** Creates a new thread-safe set for collecting discovered assertions. */
    static Set<Assertion> newAssertionSet() {
        return ConcurrentHashMap.newKeySet();
    }

    /**
     * Instruments test classes from {@code target/test-classes/} and bundles them into
     * {@code instrumentedWarsDir/test-classes.jar}.
     *
     * <p>This method should be called once, before instrumenting application sources.
     * Test classes are shared across all images.
     *
     * @return a set of all OpenDST assertions discovered in test classes
     * @throws IOException if instrumentation fails due to I/O errors
     * @throws AssertionValidationException if an assertion is invalid
     */
    Set<Assertion> instrumentTestClasses() throws IOException {
        logger.raw().info("Instrumenting test classes");
        var discovered = newAssertionSet();
        createDirectories(instrumentedWarsDir);
        var urls = collectClasspath(null);
        try (var projectLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
            var classFile = newClassFile(projectLoader);
            instrumentClassesFolder(
                    classFile,
                    basePath.resolve("target/test-classes"),
                    instrumentedWarsDir.resolve("test-classes.jar"),
                    discovered);
        }
        return discovered;
    }

    /**
     * Instruments an exploded application directory (e.g., an unpacked WAR or artifact)
     * and writes the instrumented output under {@code instrumentedWarsDir/<appName>/}.
     *
     * <p>The source directory is expected to contain a {@code WEB-INF/} layout with
     * {@code classes/}, {@code lib/}, and optionally {@code fs/}.
     *
     * @param appName   the directory name under {@code apps/} in the output JAR
     * @param sourceDir the exploded application directory to instrument
     * @return a set of all OpenDST assertions discovered during instrumentation
     * @throws IOException if instrumentation fails due to I/O errors
     * @throws AssertionValidationException if an assertion is invalid
     */
    Set<Assertion> instrumentAppDir(String appName, Path sourceDir) throws IOException {
        logger.raw().info("Instrumenting app directory '%s' from %s".formatted(appName, sourceDir));
        var discovered = newAssertionSet();
        createDirectories(instrumentedWarsDir);
        var urls = collectClasspath(sourceDir);
        try (var projectLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
            var classFile = newClassFile(projectLoader);
            var outputDir = instrumentedWarsDir.resolve(appName);
            instrumentApplicationDirectory(classFile, sourceDir, outputDir, discovered);
        }
        return discovered;
    }

    /**
     * Instruments project classes from {@code target/classes/} and runtime dependency JARs,
     * placing the output under {@code instrumentedWarsDir/<appName>/WEB-INF/}.
     *
     * <p>Used when the project does <em>not</em> use {@code maven-war-plugin}. The source
     * directories ({@code target/classes/}, {@code target/test-classes/}) are read but never
     * modified; instrumented output is written entirely to {@code instrumentedWarsDir}.
     *
     * @param appName        the application name (typically the Maven artifactId), used as
     *                       the directory name under {@code apps/} in the output JAR
     * @param dependencyJars the project's runtime dependency JARs to instrument and copy
     * @return a set of all OpenDST assertions discovered during instrumentation
     * @throws IOException if instrumentation fails due to I/O errors
     * @throws AssertionValidationException if an assertion is invalid
     */
    Set<Assertion> instrumentClasses(String appName, List<Path> dependencyJars) throws IOException {
        logger.raw().info("Instrumenting classes for %s".formatted(appName));
        var discovered = newAssertionSet();
        createDirectories(instrumentedWarsDir);
        var urls = collectClasspath(null);
        try (var projectLoader = new URLClassLoader(urls, getClass().getClassLoader())) {
            var classFile = newClassFile(projectLoader);

            // Instrument target/classes/ → <appName>/WEB-INF/classes.jar
            var webInfDir = instrumentedWarsDir.resolve(appName).resolve("WEB-INF");
            instrumentClassesFolder(
                    classFile,
                    basePath.resolve("target/classes"),
                    webInfDir.resolve("classes.jar"),
                    discovered);

            // Instrument runtime dependency JARs → <appName>/WEB-INF/lib/
            var libDir = webInfDir.resolve("lib");
            for (var jarPath : dependencyJars) {
                var targetJar = libDir.resolve(jarPath.getFileName().toString());
                createDirectories(libDir);
                instrumentJar(classFile, jarPath, targetJar, discovered);
            }
        }
        return discovered;
    }

    /**
     * Instruments an exploded application directory, walking its file tree to find
     * {@code classes/} folders and JAR files.
     */
    private void instrumentApplicationDirectory(
            ClassFile classFile, Path sourceDir, Path outputDir, Set<Assertion> discovered)
            throws IOException {
        if (!exists(sourceDir)) {
            return;
        }

        walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().equals("classes") && !dir.equals(sourceDir)) {
                    var targetJar = outputDir.resolve(sourceDir.relativize(dir) + ".jar");
                    instrumentClassesFolder(classFile, dir, targetJar, discovered);
                    return SKIP_SUBTREE;
                }
                return CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var targetFile = outputDir.resolve(sourceDir.relativize(file));
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

    /** Creates a {@link ClassFile} instance configured with the given classloader for hierarchy resolution. */
    private static ClassFile newClassFile(URLClassLoader loader) {
        return ClassFile.of(ClassHierarchyResolverOption.of(ofClassLoading(loader)
                .orElse(desc -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(
                        ClassDesc.ofDescriptor("Ljava/lang/Object;")))));
    }

    /**
     * Builds a classpath for the {@link URLClassLoader} used during transformation.
     *
     * @param appDir an exploded application directory, or {@code null} for non-WAR projects
     */
    private URL[] collectClasspath(Path appDir) throws IOException {
        var urls = new ArrayList<URL>();
        var mainClassesDir = basePath.resolve("target/classes");
        if (exists(mainClassesDir)) {
            urls.add(mainClassesDir.toUri().toURL());
        }
        var testClassesDir = basePath.resolve("target/test-classes");
        if (exists(testClassesDir)) {
            urls.add(testClassesDir.toUri().toURL());
        }
        if (appDir != null && exists(appDir)) {
            try (var stream = walk(appDir)) {
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
            ClassFile classFile, Path sourceDir, Path targetJar, Set<Assertion> discovered)
            throws IOException {
        if (!exists(sourceDir)) {
            return;
        }
        createDirectories(targetJar.getParent());
        try (var jos = new JarOutputStream(newOutputStream(targetJar));
                var stream = walk(sourceDir)) {
            instrumentEntries(classFile, jos, discovered, sourceDir, stream.filter(Files::isRegularFile));
        }
    }

    /** Instruments a JAR file and writes the result to a target location. */
    private void instrumentJar(ClassFile classFile, Path jarPath, Path targetJar, Set<Assertion> discovered)
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
            Set<Assertion> discovered,
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
