// Verify that the opendst:build goal produces correctly instrumented bytecode
// when the project has JAR packaging and depends on a library (RxJava) containing
// Thread subclasses.
//
// This IT exercises the instrumentClasses() path where dependency JARs must be
// visible to the ClassHierarchyResolver during bytecode transformation. Without
// them, stack map frame recomputation produces incorrect merge types and the
// JVM throws a VerifyError at runtime.
//
// We verify by extracting the instrumented RxJava JAR from the output and
// attempting to load RxThreadFactory — which has a conditional branch merging
// RxCustomThread (Thread subclass) and Thread. If the ClassHierarchyResolver
// could not resolve RxCustomThread, the stack map frame at the merge point
// will contain Object instead of Thread, causing a VerifyError.

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

// Locate the instrumented RxJava JAR inside the output
def instrumentedRxJava = new File(targetDir,
    "opendst-package/instrumented-apps/it-classloader/WEB-INF/lib/rxjava-3.1.10.jar")
check(instrumentedRxJava.exists(), "Instrumented rxjava JAR not found at: ${instrumentedRxJava}", logFile)

def reactiveStreams = new File(targetDir,
    "opendst-package/instrumented-apps/it-classloader/WEB-INF/lib/reactive-streams-1.0.4.jar")

// The agent JAR is needed on the classpath because instrumented code references
// ThreadsInterceptors (the deterministic Thread factory)
def agentJar = new File(targetDir, "opendst-package/opendst-agent.jar")
check(agentJar.exists(), "Agent JAR not found at: ${agentJar}", logFile)

// Write a small Java source that forces the JVM to load and verify RxThreadFactory
def testDir = new File(targetDir, "verify-classloader")
testDir.mkdirs()

def testSource = new File(testDir, "VerifyRxThreadFactory.java")
testSource.text = '''
import io.reactivex.rxjava3.internal.schedulers.RxThreadFactory;
public class VerifyRxThreadFactory {
    public static void main(String[] args) throws Exception {
        // Force the class to be loaded and verified
        var factory = new RxThreadFactory("test");
        var thread = factory.newThread(() -> {});
        System.out.println("OK: RxThreadFactory loaded and newThread() succeeded: " + thread.getClass().getName());
    }
}
'''

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath
def javacBin = new File(javaHome, "bin/javac").absolutePath

// Build the classpath for compilation and execution
def cp = [instrumentedRxJava.absolutePath, reactiveStreams.absolutePath, agentJar.absolutePath].join(File.pathSeparator)

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
println "Running with -Xverify:all to check stack map frames..."
def runProc = new ProcessBuilder(javaBin, "-Xverify:all", "-cp", runCp, "VerifyRxThreadFactory")
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

println "All verifications passed — instrumented RxThreadFactory has correct stack map frames."
