// Verify the build mojo produced a self-contained JAR for the flaky-test,
// then run the JAR and verify that non-determinism is detected.
File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

// Helper to avoid Power Assert dumping logContent
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

// Check instrumentation ran
check(logContent.contains("Instrumenting"), "OpenDST instrumentation was not found", logFile)

// Check the build mojo ran successfully
check(logContent.contains("Built self-contained JAR"), "Build mojo did not complete", logFile)

// Check the JAR file was created
def targetDir = new File(basedir, "target")
def jarFiles = targetDir.listFiles({ dir, name -> name.endsWith("-opendst.jar") } as FilenameFilter)
check(jarFiles != null && jarFiles.length == 1, "Expected exactly one *-opendst.jar in target/, found: ${jarFiles?.length ?: 0}", logFile)
File jarFile = jarFiles[0]
check(jarFile.length() > 0, "${jarFile.name} is empty", logFile)

// ---- Phase 2: Run the built JAR and verify flaky detection ----

import groovy.json.JsonSlurper

def workingDir = new File(basedir, "target/opendst-work")

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

println "Running: ${javaBin} -jar ${jarFile.absolutePath} --working-dir ${workingDir.absolutePath}"

// High replay probability ensures replays happen quickly.
// The scenario uses RealTime.currentTimeMillis() which bypasses the virtual clock,
// so replayed plans will produce different hashes and be flagged as flaky.
// --stop first-fail causes the runner to stop as soon as non-determinism is detected.
def process = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                                 "--working-dir", workingDir.absolutePath,
                                 "--fork-count", "1",
                                 "--stagnation-limit", "20",
                                 "--replay-probability", "0.95",
                                 "--stop", "first-fail")
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

// Capture output for debugging
def output = new StringBuilder()
process.inputStream.eachLine { line ->
    output.append(line).append("\n")
    println "[JAR] ${line}"
}

def exitCode = process.waitFor()

// Assertion failures detected, so exit code should be non-zero
check(exitCode != 0, "Expected non-zero exit code (assertion failures), got: ${exitCode}", logFile)

// Verify the report was produced
def reportFile = new File(workingDir, "report/report.json")
check(reportFile.exists(), "report.json was not created", logFile)
check(reportFile.length() > 0, "report.json is empty", logFile)

def report = new JsonSlurper().parseText(reportFile.text)
check(report.count > 0, "report.count should be > 0", logFile)

// The "no internal error" assertion should have failures due to non-determinism.
// When the Simulator detects a hash mismatch during replay, it emits a "non-determinism detected"
// lifecycle signal, which fails the "no internal error" assertion with expectedHash/actualHash details.
def internalErrorAssertion = report.assertions.find { it.name == "no internal error" }
check(internalErrorAssertion != null, "'no internal error' assertion missing from report", logFile)
check(internalErrorAssertion.pass == false, "Expected 'no internal error' to fail (non-determinism detected), got: ${internalErrorAssertion.pass}", logFile)

// Verify that at least one failure is due to non-determinism detection (has expectedHash/actualHash)
def nonDetFailures = internalErrorAssertion.examples?.failExamples?.findAll { it.details?.expectedHash != null }
check(nonDetFailures != null && nonDetFailures.size() > 0,
      "No non-determinism failures found — expected at least one replay hash mismatch", logFile)

println "Non-determinism failures detected: ${nonDetFailures.size()} replay(s) produced different hashes."

// Verify that some runs also succeeded (fresh runs should complete normally)
def terminatedAssertion = report.assertions.find { it.name == "simulation terminated" }
check(terminatedAssertion != null, "'simulation terminated' assertion missing from report", logFile)
check(terminatedAssertion.examples?.passCount > 0,
      "Expected some successful runs (fresh exploration), but none found", logFile)

println "All verifications passed — flaky-test correctly detects non-determinism via replay hash mismatch."
