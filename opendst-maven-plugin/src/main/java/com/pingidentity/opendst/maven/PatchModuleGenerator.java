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

import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.walk;
import static java.nio.file.Files.write;
import static java.nio.file.Files.writeString;
import static java.util.Comparator.reverseOrder;
import static java.util.List.of;
import static java.util.jar.Attributes.Name.MANIFEST_VERSION;
import static javax.tools.ToolProvider.getSystemJavaCompiler;

import java.io.IOException;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;

/**
 * Generates the {@code opendst-patch.jar} containing:
 * <ol>
 *   <li>{@code java/lang/VirtualThread.class} — with {@code ACC_FINAL} stripped</li>
 *   <li>{@code java/lang/SimulatorThread.class} — compiled from source, extends non-final
 *       {@code VirtualThread}, mirrors all {@code Thread} constructors</li>
 * </ol>
 *
 * <p>This JAR is used with {@code --patch-module java.base=opendst-patch.jar} to allow
 * Thread subclasses (rewritten to extend {@code SimulatorThread}) to run as virtual threads
 * under the deterministic simulator.
 *
 * <p><strong>JDK version constraint:</strong> The patched {@code VirtualThread.class} is read
 * byte-for-byte from the build JDK's runtime image ({@code jrt:/}), and {@code SimulatorThread}
 * is compiled against it. This means the generated JAR is tied to the exact JDK version used
 * during the Maven build. Running the simulation on a different JDK major version will fail at
 * startup with a version mismatch error. Running on a different update/patch of the same major
 * version will produce a warning. To fix either case, rebuild the project with the target JDK.
 *
 * <p>The generation process:
 * <ol>
 *   <li>Read {@code VirtualThread.class} from the JDK runtime image and strip {@code ACC_FINAL}</li>
 *   <li>Write the patched class to a temp directory</li>
 *   <li>Compile {@code SimulatorThread.java} (bundled as a classpath resource) with
 *       {@code --patch-module java.base=<tempDir>} so it sees the non-final VirtualThread</li>
 *   <li>Package both classes into {@code opendst-patch.jar} with a build JDK version manifest</li>
 * </ol>
 */
final class PatchModuleGenerator {

    /** MANIFEST attribute recording the JDK version used at build time. */
    static final String BUILD_JDK_VERSION_ATTR = "Build-Jdk-Version";

    /** Classpath resource containing the SimulatorThread source. */
    private static final String SIMULATOR_THREAD_SOURCE = "com/pingidentity/opendst/maven/SimulatorThread.java";

    /**
     * Generates {@code opendst-patch.jar} at the given path.
     *
     * <p>The generated JAR contains a MANIFEST with a {@code Build-Jdk-Version} attribute
     * recording the exact JDK runtime version used during the build. This enables a runtime
     * check to ensure the simulation JDK matches the build JDK.
     *
     * @param outputJar the path where the JAR will be written
     * @throws IOException if an I/O error occurs
     */
    static void generate(Path outputJar) throws IOException {
        createDirectories(outputJar.getParent());

        var tempDir = createTempDirectory("opendst-patch");
        try {
            // 1. Patch VirtualThread: strip ACC_FINAL and write to temp dir
            var patchedVT = patchVirtualThread();
            createDirectories(tempDir.resolve("java/lang"));
            write(tempDir.resolve("java/lang/VirtualThread.class"), patchedVT);

            // 2. Compile SimulatorThread.java against the patched VirtualThread
            var compiledST = compileSimulatorThread(tempDir);

            // 3. Package into opendst-patch.jar
            var manifest = new Manifest();
            manifest.getMainAttributes().put(MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes()
                    .putValue(BUILD_JDK_VERSION_ATTR, Runtime.version().toString());
            try (var jos = new JarOutputStream(newOutputStream(outputJar), manifest)) {
                addEntry(jos, "java/lang/VirtualThread.class", patchedVT);
                addEntry(jos, "java/lang/SimulatorThread.class", compiledST);
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Reads {@code VirtualThread.class} from the JDK runtime image and strips {@code ACC_FINAL}.
     */
    private static byte[] patchVirtualThread() throws IOException {
        var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        var originalBytes = readAllBytes(jrt.getPath("/modules/java.base/java/lang/VirtualThread.class"));

        var cf = ClassFile.of();
        var model = cf.parse(originalBytes);
        int newFlags = model.flags().flagsMask() & ~ACC_FINAL;
        return cf.transformClass(model, (builder, element) -> {
            if (element instanceof AccessFlags) {
                builder.withFlags(newFlags);
            } else {
                builder.with(element);
            }
        });
    }

    /**
     * Compiles {@code SimulatorThread.java} from the classpath resource against a patched
     * VirtualThread (with ACC_FINAL stripped) using {@code javax.tools.JavaCompiler}.
     *
     * @param patchDir directory containing the patched {@code java/lang/VirtualThread.class}
     * @return the compiled {@code SimulatorThread.class} bytes
     */
    private static byte[] compileSimulatorThread(Path patchDir) throws IOException {
        // Read source from classpath resource
        String source;
        try (var sourceStream =
                PatchModuleGenerator.class.getClassLoader().getResourceAsStream(SIMULATOR_THREAD_SOURCE)) {
            if (sourceStream == null) {
                throw new IOException(
                        "SimulatorThread.java resource not found on classpath: " + SIMULATOR_THREAD_SOURCE);
            }
            source = new String(sourceStream.readAllBytes(), UTF_8);
        }

        // Write source to temp dir for javac
        var sourceFile = patchDir.resolve("src/java/lang/SimulatorThread.java");
        createDirectories(sourceFile.getParent());
        writeString(sourceFile, source);

        // Compile with --patch-module so javac sees the non-final VirtualThread
        var outputDir = patchDir.resolve("classes");
        createDirectories(outputDir);

        var compiler = getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("No system Java compiler available (javax.tools.JavaCompiler). "
                    + "Ensure the build runs on a JDK, not a JRE.");
        }

        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, UTF_8)) {
            var compilationUnits = fileManager.getJavaFileObjects(sourceFile.toFile());
            var options = of("--patch-module", "java.base=" + patchDir, "-d", outputDir.toString());

            var task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
            if (!task.call()) {
                var errors = new StringBuilder("Failed to compile SimulatorThread.java:\n");
                for (var d : diagnostics.getDiagnostics()) {
                    errors.append("  ").append(d).append('\n');
                }
                throw new IOException(errors.toString());
            }
        }

        // Read the compiled class
        var classFile = outputDir.resolve("java/lang/SimulatorThread.class");
        if (!exists(classFile)) {
            throw new IOException("Compiled SimulatorThread.class not found at: " + classFile);
        }
        return readAllBytes(classFile);
    }

    private static void addEntry(JarOutputStream jos, String name, byte[] content) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content);
        jos.closeEntry();
    }

    /** Recursively deletes a directory tree. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void deleteRecursively(Path dir) {
        try (var stream = walk(dir)) {
            stream.sorted(reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // Best-effort cleanup of temp directory
        }
    }

    private PatchModuleGenerator() {}
}
