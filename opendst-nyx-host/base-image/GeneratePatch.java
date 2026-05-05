import java.io.*;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.net.URI;
import java.nio.file.*;
import java.util.List;
import java.util.jar.*;
import javax.tools.*;

/**
 * Generates opendst-patch.jar containing a non-final VirtualThread and SimulatorThread,
 * compiled against the JDK that runs this program.
 *
 * Usage: java GeneratePatch.java <SimulatorThread.java> <output.jar>
 */
public class GeneratePatch {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java GeneratePatch.java <SimulatorThread.java> <output.jar>");
            System.exit(1);
        }
        var simulatorThreadSource = Path.of(args[0]);
        var outputJar = Path.of(args[1]);

        var tempDir = Files.createTempDirectory("opendst-patch");
        try {
            // 1. Read VirtualThread.class from this JDK and strip ACC_FINAL
            var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            var vtBytes = Files.readAllBytes(jrt.getPath("/modules/java.base/java/lang/VirtualThread.class"));
            var cf = ClassFile.of();
            var model = cf.parse(vtBytes);
            int newFlags = model.flags().flagsMask() & ~ClassFile.ACC_FINAL;
            var patchedVT = cf.transformClass(model, (builder, element) -> {
                if (element instanceof AccessFlags) builder.withFlags(newFlags);
                else builder.with(element);
            });

            // 2. Write patched VirtualThread to temp dir for javac
            var vtOut = tempDir.resolve("java/lang/VirtualThread.class");
            Files.createDirectories(vtOut.getParent());
            Files.write(vtOut, patchedVT);

            // 3. Copy SimulatorThread.java into a src tree so javac finds it
            var srcFile = tempDir.resolve("src/java/lang/SimulatorThread.java");
            Files.createDirectories(srcFile.getParent());
            Files.copy(simulatorThreadSource, srcFile, StandardCopyOption.REPLACE_EXISTING);

            // 4. Compile SimulatorThread against the patched VirtualThread
            var classesDir = tempDir.resolve("classes");
            Files.createDirectories(classesDir);
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IllegalStateException("No javac available — run with JDK, not JRE");
            var diag = new DiagnosticCollector<JavaFileObject>();
            try (var fm = compiler.getStandardFileManager(diag, null, null)) {
                var units = fm.getJavaFileObjects(srcFile.toFile());
                var options = List.of("--patch-module", "java.base=" + tempDir, "-d", classesDir.toString());
                if (!compiler.getTask(null, fm, diag, options, null, units).call()) {
                    diag.getDiagnostics().forEach(d -> System.err.println(d));
                    throw new IllegalStateException("Compilation failed");
                }
            }
            var stBytes = Files.readAllBytes(classesDir.resolve("java/lang/SimulatorThread.class"));

            // 5. Package into output JAR with Build-Jdk-Version manifest entry
            Files.createDirectories(outputJar.getParent());
            var mf = new Manifest();
            mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mf.getMainAttributes().putValue("Build-Jdk-Version", Runtime.version().toString());
            try (var jos = new JarOutputStream(new FileOutputStream(outputJar.toFile()), mf)) {
                addEntry(jos, "java/lang/VirtualThread.class", patchedVT);
                addEntry(jos, "java/lang/SimulatorThread.class", stBytes);
            }
            System.out.println("Generated " + outputJar + " (JDK " + Runtime.version() + ")");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void addEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(bytes);
        jos.closeEntry();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
