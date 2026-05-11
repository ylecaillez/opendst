import groovy.json.JsonSlurper

// Verify the it-fray integration test: schedule-dependent concurrency bugs derived
// from the Fray project (https://github.com/cmu-pasta/fray, Apache 2.0) that the
// simulator is expected to detect via schedule exploration.
//
// Phase 1 — Build: verifies the JAR was produced with the expected assertion labels.
// Phase 2 — Run:   executes the JAR and verifies every bug-detection assertion passed
//                  (i.e., the simulator found a schedule that triggered each bug).

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

// ---- Phase 1: Build verification ------------------------------------------------

check(logContent.contains("Instrumenting"), "OpenDST instrumentation was not found", logFile)
check(logContent.contains("Built self-contained JAR"), "Build mojo did not complete", logFile)

def targetDir = new File(basedir, "target")
def jarFiles = targetDir.listFiles({ dir, name -> name.endsWith("-opendst.jar") } as FilenameFilter)
check(jarFiles != null && jarFiles.length == 1,
        "Expected exactly one *-opendst.jar in target/, found: ${jarFiles?.length ?: 0}", logFile)
File jarFile = jarFiles[0]
check(jarFile.length() > 0, "${jarFile.name} is empty", logFile)

// Each Assert call in FrayBugSuite must be discovered by offline instrumentation
def expectedLabels = [
    "synchronized-method-deadlock",
    "notify-order",
    "wait-without-monitor-lock/always-throws",
]

import java.util.jar.JarFile
def jar = new JarFile(jarFile)
try {
    check(jar.getEntry("system/opendst-patch.jar") != null,
            "opendst-patch.jar missing from system/ in output JAR", logFile)

    def assertionsEntry = jar.getEntry("META-INF/opendst/assertions.json")
    check(assertionsEntry != null, "assertions.json missing from JAR", logFile)
    def assertions = new JsonSlurper().parseText(jar.getInputStream(assertionsEntry).text)
    check(assertions instanceof List, "assertions.json is not a list", logFile)
    def labels = assertions.collect { it.message }
    for (label in expectedLabels) {
        check(labels.contains(label),
                "assertion '${label}' not found in assertions.json: ${labels}", logFile)
    }
} finally {
    jar.close()
}

// ---- Phase 2: Run and verify bug detection ---------------------------------------

def workingDir = new File(basedir, "target/opendst-work")
def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

println "Running simulation to verify bug detection..."
def process = new ProcessBuilder(
            javaBin, "-jar", jarFile.absolutePath,
            "--working-dir", workingDir.absolutePath,
            "--stagnation-limit", "50")
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

def output = new StringBuilder()
process.inputStream.eachLine { line ->
    output.append(line).append("\n")
    println "[JAR] ${line}"
}
def exitCode = process.waitFor()
check(exitCode == 0, "java -jar failed with exit code ${exitCode}", logFile)

def reportFile = new File(workingDir, "report/report.json")
check(reportFile.exists(), "report.json was not created in ${reportFile.parentFile.absolutePath}", logFile)
check(reportFile.length() > 0, "report.json is empty", logFile)

def report = new JsonSlurper().parseText(reportFile.text)
check(report.count != null && report.count > 0, "report.count should be > 0, got: ${report.count}", logFile)

def reportAssertions = report.assertions.collectEntries { [it.name, it] }
println "Report assertions: ${reportAssertions.keySet()}"

// Every expected assertion must be present and must have passed
for (label in expectedLabels) {
    check(reportAssertions.containsKey(label),
            "assertion '${label}' not found in report: ${reportAssertions.keySet()}", logFile)
    check(reportAssertions[label].pass,
            "Bug NOT detected — assertion '${label}' did not pass (simulator did not find the bug)",
            logFile)
}

println "All ${expectedLabels.size()} bug-detection assertions passed — each bug was found by the simulator."
