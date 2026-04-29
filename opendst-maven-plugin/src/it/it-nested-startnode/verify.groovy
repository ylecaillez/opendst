import groovy.json.JsonSlurper

// Regression test: Node#execute() must restore System.out/System.err symmetrically with
// CURRENT_NODE when an inner Simulator.startNode(...) call re-enters execute() on the
// same carrier thread.

File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

def check(boolean condition, String message, File file) {
    if (!condition) {
        println "Verification failed: ${message}"
        def lines = file.readLines()
        def start = Math.max(0, lines.size() - 100)
        println "--- build.log tail ---"
        lines[start..-1].each { println it }
        println "----------------------"
        assert false : message
    }
}

check(logContent.contains("Built self-contained JAR"), "Build mojo did not complete", logFile)

def targetDir = new File(basedir, "target")
def jarFiles = targetDir.listFiles({ dir, name -> name.endsWith("-opendst.jar") } as FilenameFilter)
check(jarFiles != null && jarFiles.length == 1,
      "Expected exactly one *-opendst.jar in target/, found: ${jarFiles?.length ?: 0}", logFile)
File jarFile = jarFiles[0]

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

def workingDir = new File(basedir, "target/opendst-work")

println "Running simulation..."
def proc = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                              "--working-dir", workingDir.absolutePath,
                              "--stagnation-limit", "20",
                              "--replay-probability", "0.0",
                              "--stop", "any-fail")
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

def output = new StringBuilder()
proc.inputStream.eachLine { line ->
    output.append(line).append("\n")
    println "[JAR] ${line}"
}

def exitCode = proc.waitFor()
check(exitCode == 0, "Expected exit code 0 (no failures), got: ${exitCode}", logFile)

def reportFile = new File(workingDir, "report/report.json")
assert reportFile.exists() : "report.json was not created"
assert reportFile.length() > 0 : "report.json is empty"

def report = new JsonSlurper().parseText(reportFile.text)
def reportAssertions = report.assertions.collectEntries { [it.name, it] }
println "Report assertions: ${reportAssertions.keySet()}"

// Required reachability — proves the test code path actually executed.
def expected = [
    "parent-main-started",
    "worker-scenario-started",
    "parent-main-resumed-after-nested-startnode",
    "System.out preserved across nested Simulator.startNode",
    "System.err preserved across nested Simulator.startNode",
]
for (a in expected) {
    check(reportAssertions.containsKey(a),
          "assertion '${a}' not found in report: ${reportAssertions.keySet()}", logFile)
}

// The bug: alwaysOrUnreachable on System.out / System.err preservation must pass.
check(reportAssertions["System.out preserved across nested Simulator.startNode"].pass == true,
      "System.out was NOT restored after nested Simulator.startNode — Node#execute asymmetric restore", logFile)
check(reportAssertions["System.err preserved across nested Simulator.startNode"].pass == true,
      "System.err was NOT restored after nested Simulator.startNode — Node#execute asymmetric restore", logFile)

// Built-in lifecycle assertions must also pass.
assert reportAssertions["simulation started"]?.pass == true
assert reportAssertions["simulation terminated"]?.pass == true
assert reportAssertions["no internal error"]?.pass == true
assert reportAssertions["no uncaught exception"]?.pass == true

println "All verifications passed."
