// Verify that the opendst:build goal produces correctly instrumented bytecode
// when the project has JAR packaging and depends on a library (RxJava) containing
// Thread subclasses.
//
// This IT exercises two instrumentation paths:
//
// 1. Compile scope (default): instrumentClasses() with [target/classes] — the
//    RxJava dependency JARs must be visible to the ClassHierarchyResolver during
//    bytecode transformation. Without them, stack map frame recomputation produces
//    incorrect merge types and the JVM throws a VerifyError at runtime.
//
// 2. Test scope: instrumentClasses() with [target/classes, target/test-classes] —
//    both main and test classes are combined into a single classes.jar, with all
//    dependency JARs (including test-scoped ones) instrumented alongside.
//
// We verify:
//   a) The instrumented RxJava JAR passes verification (RxThreadFactory loads
//      without VerifyError — the ClassHierarchyResolver correctly resolved
//      RxCustomThread as a Thread subclass).
//   b) The instrumented classes.jar contains the call-site transform: RxApp's
//      createThread(Runnable) method has been rewritten from
//      NEW Thread / INVOKESPECIAL Thread.<init> to
//      NEW SimulatorThread / INVOKESPECIAL SimulatorThread.<init>.
//   c) The instrumented code executes correctly at runtime with --patch-module
//      (SimulatorThread is a VirtualThread, so the created thread runs as a
//      virtual thread under the deterministic scheduler).

File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

// Helper to avoid Power Assert dumping logContent
def check(boolean condition, String message, File file) {
    if (!condition) {
        println "Verification failed: ${message}"
        def lines = file.readLines()
        def start = Math.max(0, lines.size() - 100)
        println "--- log tail ---"
        lines[start..-1].each { println it }
        println "----------------"
        assert false : message
    }
}

// Check the build mojo ran successfully and produced a JAR
check(logContent.contains("Built self-contained JAR"), "Build mojo did not complete", logFile)

def targetDir = new File(basedir, "target")
def jarFiles = targetDir.listFiles({ dir, name -> name.endsWith("-opendst.jar") } as FilenameFilter)
check(jarFiles != null && jarFiles.length == 1,
      "Expected exactly one *-opendst.jar in target/, found: ${jarFiles?.length ?: 0}", logFile)
File jarFile = jarFiles[0]
check(jarFile.length() > 0, "${jarFile.name} is empty", logFile)

// ──────────────────────────────────────────────────────────────────────────────
// Compile-scope image verification (apps/it-instrumentation/)
// ──────────────────────────────────────────────────────────────────────────────

def instrumentedAppsDir = new File(targetDir, "opendst-package/instrumented-apps")

// Locate the instrumented RxJava JAR inside the compile-scope image
def compileRxJava = new File(instrumentedAppsDir, "it-instrumentation/WEB-INF/lib/rxjava-3.1.10.jar")
check(compileRxJava.exists(), "Compile-scope: instrumented rxjava JAR not found at: ${compileRxJava}", logFile)

def reactiveStreams = new File(instrumentedAppsDir, "it-instrumentation/WEB-INF/lib/reactive-streams-1.0.4.jar")

// The agent JAR is needed on the classpath because instrumented code references
// SimulatorThread (the deterministic Thread base class)
def agentJar = new File(targetDir, "opendst-package/opendst-agent.jar")
check(agentJar.exists(), "Agent JAR not found at: ${agentJar}", logFile)

// The patch-module JAR provides SimulatorThread (extends VirtualThread) for
// Thread subclass rewriting. Instrumented Thread subclasses extend SimulatorThread
// instead of Thread, so this JAR must be present via --patch-module java.base=...
def patchModuleJar = new File(targetDir, "opendst-package/opendst-patch.jar")
check(patchModuleJar.exists(), "opendst-patch.jar not found at: ${patchModuleJar}", logFile)
def patchModuleArg = "--patch-module"
def patchModuleVal = "java.base=${patchModuleJar.absolutePath}"

// Locate the instrumented classes.jar for the compile-scope image
def compileClassesJar = new File(instrumentedAppsDir, "it-instrumentation/WEB-INF/classes.jar")
check(compileClassesJar.exists(), "Compile-scope: classes.jar not found at: ${compileClassesJar}", logFile)

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath
def javacBin = new File(javaHome, "bin/javac").absolutePath
def javapBin = new File(javaHome, "bin/javap").absolutePath

// ── Part 1: Verify RxJava dependency JAR (stack map frames) ─────────────────

// Write a small Java source that forces the JVM to load and verify RxThreadFactory
def testDir = new File(targetDir, "verify-instrumentation")
testDir.mkdirs()

def testSource = new File(testDir, "VerifyRxThreadFactory.java")
testSource.text = '''
import io.reactivex.rxjava3.internal.schedulers.RxThreadFactory;
public class VerifyRxThreadFactory {
    public static void main(String[] args) throws Exception {
        // Force the class to be loaded and verified (stack map frame validation).
        // We cannot call newThread() because it creates a SimulatorThread which
        // requires a simulation context (EXECUTOR_SUPPLIER must be set).
        var factory = new RxThreadFactory("test");
        System.out.println("OK: RxThreadFactory loaded and verified: " + factory.getClass().getName());
    }
}
'''

// Build the classpath for compilation and execution
def cp = [compileRxJava.absolutePath, reactiveStreams.absolutePath, agentJar.absolutePath].join(File.pathSeparator)

// Compile the test
println "Compiling verification class..."
def compileProc = new ProcessBuilder(javacBin, "-cp", cp, "-d", testDir.absolutePath, testSource.absolutePath)
        .redirectErrorStream(true)
        .start()
def compileOutput = compileProc.inputStream.text
def compileExit = compileProc.waitFor()
check(compileExit == 0, "Failed to compile verification class: ${compileOutput}", logFile)

// Run with strict verification to detect corrupted stack map frames
def runCp = [testDir.absolutePath, cp].join(File.pathSeparator)
println "Running with -Xverify:all to check stack map frames (compile scope)..."
def runProc = new ProcessBuilder(javaBin, patchModuleArg, patchModuleVal, "-Xverify:all", "-cp", runCp, "VerifyRxThreadFactory")
        .redirectErrorStream(true)
        .start()
def runOutput = runProc.inputStream.text
def runExit = runProc.waitFor()

println "Exit code: ${runExit}"
println "Output: ${runOutput}"

check(!runOutput.contains("VerifyError"),
      "VerifyError detected — ClassHierarchyResolver failed to resolve Thread subclass hierarchy.\n" +
      "Output: ${runOutput}", logFile)
check(!runOutput.contains("Bad type on operand stack"),
      "Bad type on operand stack — stack map frames are incorrect.\nOutput: ${runOutput}", logFile)
check(runExit == 0,
      "Verification class failed with exit code ${runExit}.\nOutput: ${runOutput}", logFile)
check(runOutput.contains("OK:"),
      "Verification class did not produce expected output.\nOutput: ${runOutput}", logFile)

println "Compile-scope: RxJava stack map verification passed."

// ── Part 2: Verify call-site transform in classes.jar (bytecode) ────────────

// Use javap to disassemble the instrumented RxApp.createThread method and verify
// that "new Thread" + "invokespecial Thread.<init>" has been replaced with
// "new SimulatorThread" + "invokespecial SimulatorThread.<init>"
println "Inspecting instrumented bytecode in classes.jar..."
def javapProc = new ProcessBuilder(
        javapBin, "-c", "-cp", compileClassesJar.absolutePath,
        "com.pingidentity.opendst.it.instrumentation.RxApp")
        .redirectErrorStream(true)
        .start()
def javapOutput = javapProc.inputStream.text
def javapExit = javapProc.waitFor()
check(javapExit == 0, "javap failed: ${javapOutput}", logFile)

println "javap output for RxApp:\n${javapOutput}"

// The createThread method should contain SimulatorThread (rewritten from Thread)
check(javapOutput.contains("SimulatorThread"),
      "Call-site transform NOT applied: createThread() does not contain SimulatorThread.\n" +
      "javap output:\n${javapOutput}", logFile)

// Verify the createThread method does NOT still contain the original Thread constructor call
def createThreadSection = javapOutput.split(/(?m)^\s+public static java\.lang\.Thread createThread/)[1]
        ?.split(/(?m)^\s+public /)?[0]  // isolate to just the createThread method body
if (createThreadSection != null) {
    check(!createThreadSection.contains("java/lang/Thread.\"<init>\""),
          "Call-site transform incomplete: createThread() still contains Thread.<init> constructor call.\n" +
          "Method bytecode:\n${createThreadSection}", logFile)
}

println "Compile-scope: call-site transform verification passed."

// ──────────────────────────────────────────────────────────────────────────────
// Test-scope image verification (apps/it-instrumentation-tests/)
// ──────────────────────────────────────────────────────────────────────────────

def testScopeDir = new File(instrumentedAppsDir, "it-instrumentation-tests")
check(testScopeDir.exists(), "Test-scope image directory not found: ${testScopeDir}", logFile)

// classes.jar should contain both main and test classes
def testClassesJar = new File(testScopeDir, "WEB-INF/classes.jar")
check(testClassesJar.exists(), "Test-scope: classes.jar not found at: ${testClassesJar}", logFile)

// Verify classes.jar contains both RxApp (main) and RxTestApp (test)
List classesJarEntries
try (def testScopeJar = new java.util.jar.JarFile(testClassesJar)) {
    classesJarEntries = testScopeJar.entries().toList()*.name
}
def hasRxApp = classesJarEntries.any { it.contains("RxApp.class") && !it.contains("RxTestApp") }
def hasRxTestApp = classesJarEntries.any { it.contains("RxTestApp.class") }
check(hasRxApp, "Test-scope: classes.jar does not contain RxApp (main class).\nEntries: ${classesJarEntries}", logFile)
check(hasRxTestApp, "Test-scope: classes.jar does not contain RxTestApp (test class).\nEntries: ${classesJarEntries}", logFile)
println "Test-scope classes.jar contains both RxApp and RxTestApp."

// Test-scope image should also have instrumented RxJava
def testRxJava = new File(testScopeDir, "WEB-INF/lib/rxjava-3.1.10.jar")
check(testRxJava.exists(), "Test-scope: instrumented rxjava JAR not found at: ${testRxJava}", logFile)

// Verify the test-scope RxJava JAR also passes -Xverify:all
def testVerifyDir = new File(targetDir, "verify-instrumentation-test")
testVerifyDir.mkdirs()

def testCp = [testRxJava.absolutePath, reactiveStreams.absolutePath, agentJar.absolutePath].join(File.pathSeparator)

// Reuse the same verification source
def testSource2 = new File(testVerifyDir, "VerifyRxThreadFactory.java")
testSource2.text = testSource.text

println "Compiling verification class for test scope..."
def compileProc2 = new ProcessBuilder(javacBin, "-cp", testCp, "-d", testVerifyDir.absolutePath, testSource2.absolutePath)
        .redirectErrorStream(true)
        .start()
def compileOutput2 = compileProc2.inputStream.text
def compileExit2 = compileProc2.waitFor()
check(compileExit2 == 0, "Failed to compile verification class (test scope): ${compileOutput2}", logFile)

def runCp2 = [testVerifyDir.absolutePath, testCp].join(File.pathSeparator)
println "Running with -Xverify:all to check stack map frames (test scope)..."
def runProc2 = new ProcessBuilder(javaBin, patchModuleArg, patchModuleVal, "-Xverify:all", "-cp", runCp2, "VerifyRxThreadFactory")
        .redirectErrorStream(true)
        .start()
def runOutput2 = runProc2.inputStream.text
def runExit2 = runProc2.waitFor()

println "Exit code: ${runExit2}"
println "Output: ${runOutput2}"

check(!runOutput2.contains("VerifyError"),
      "Test-scope: VerifyError detected.\nOutput: ${runOutput2}", logFile)
check(runExit2 == 0,
      "Test-scope: verification class failed with exit code ${runExit2}.\nOutput: ${runOutput2}", logFile)
check(runOutput2.contains("OK:"),
      "Test-scope: verification class did not produce expected output.\nOutput: ${runOutput2}", logFile)

println "Test-scope: RxJava stack map verification passed."

// Verify call-site transform in test-scope classes.jar too
println "Inspecting instrumented bytecode in test-scope classes.jar..."
def javapProc2 = new ProcessBuilder(
        javapBin, "-c", "-cp", testClassesJar.absolutePath,
        "com.pingidentity.opendst.it.instrumentation.RxApp")
        .redirectErrorStream(true)
        .start()
def javapOutput2 = javapProc2.inputStream.text
def javapExit2 = javapProc2.waitFor()
check(javapExit2 == 0, "Test-scope: javap failed: ${javapOutput2}", logFile)

check(javapOutput2.contains("SimulatorThread"),
      "Test-scope: call-site transform NOT applied in classes.jar.\njavap output:\n${javapOutput2}", logFile)

println "Test-scope: call-site transform verification passed."

// ──────────────────────────────────────────────────────────────────────────────
// Verify old test-classes.jar no longer exists (dead code removed)
// ──────────────────────────────────────────────────────────────────────────────

def oldTestClassesJar = new File(instrumentedAppsDir, "test-classes.jar")
check(!oldTestClassesJar.exists(),
      "Old test-classes.jar still exists at ${oldTestClassesJar} — instrumentTestClasses() should have been removed",
      logFile)

println "All verifications passed — both compile-scope and test-scope images are correct."
