import groovy.json.JsonSlurper

// Verify the build mojo produced a self-contained JAR for the testapp,
// then run the JAR and verify the simulation report.
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

// Verify JAR contents
import java.util.jar.JarFile
def jar = new JarFile(jarFile)
try {
    // Check manifest
    def manifest = jar.manifest
    check(manifest != null, "JAR has no manifest", logFile)
    def mainClass = manifest.mainAttributes.getValue("Main-Class")
    check(mainClass == "com.pingidentity.opendst.runner.Bootstrap", "Main-Class is wrong: " + mainClass, logFile)

    // Check essential entries exist
    check(jar.getEntry("META-INF/opendst/assertions.json") != null, "assertions.json missing", logFile)
    check(jar.getEntry("system/opendst-agent.jar") != null, "opendst-agent.jar missing", logFile)
    check(jar.getEntry("deployment.yaml") != null, "deployment.yaml missing", logFile)
    check(jar.getEntry("build-config.json") != null, "build-config.json missing", logFile)

    // Check apps/ has instrumented application content
    def hasApps = jar.entries().any { it.name.startsWith("apps/") }
    check(hasApps, "No application content in apps/", logFile)

    // Check that instrumented classes.jar exists
    def hasClassesJar = jar.entries().any { it.name.contains("WEB-INF/classes.jar") }
    check(hasClassesJar, "No WEB-INF/classes.jar in apps/", logFile)

    // Verify assertions.json contains the expected assertions from SecretSequenceApp
    def assertionsEntry = jar.getEntry("META-INF/opendst/assertions.json")
    def assertionsJson = jar.getInputStream(assertionsEntry).text
    def assertions = new JsonSlurper().parseText(assertionsJson)
    def labels = assertions.collect { it.message }
    check(labels.contains("level-1"), "assertion 'level-1' not found in assertions.json: " + labels, logFile)
    check(labels.contains("level-2"), "assertion 'level-2' not found in assertions.json: " + labels, logFile)

    // Verify build-config.json contains faults configuration with network enabled
    def buildConfigEntry = jar.getEntry("build-config.json")
    def buildConfigJson = jar.getInputStream(buildConfigEntry).text
    def buildConfig = new JsonSlurper().parseText(buildConfigJson)
    check(buildConfig.faults.network.enabled == true, "Network faults should be enabled", logFile)
} finally {
    jar.close()
}

// ---- Phase 2: Run the built JAR and verify the simulation report ----

def workingDir = new File(basedir, "target/opendst-work")

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

println "Running: ${javaBin} -jar ${jarFile.absolutePath} --working-dir ${workingDir.absolutePath}"

def process = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                                 "--working-dir", workingDir.absolutePath,
                                 "--stagnation-limit", "500",
                                 "--replay-probability", "0.01",
                                 "--mode", "verify")
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

// --mode verify: the TraceAuditor detects "Bug reached!" and throws, causing the simulation
// to stop with reason=failure. This makes the "simulation stopped successfully" ALWAYS assertion
// fail, so --mode verify exits with code 1. That is the expected outcome.
check(exitCode == 1, "Expected exit code 1 (--mode verify with bug found), got: ${exitCode}", logFile)

// Verify the report was produced
def reportFile = new File(workingDir, "report/report.json")
assert reportFile.exists() : "report.json was not created in ${reportFile.parentFile.absolutePath}"
assert reportFile.length() > 0 : "report.json is empty"

def report = new JsonSlurper().parseText(reportFile.text)

// Verify report structure
assert report.count != null : "report.count is missing"
assert report.count > 0 : "report.count should be > 0, got: ${report.count}"
assert report.duration != null : "report.duration is missing"
assert report.assertions != null : "report.assertions is missing"
assert report.assertions instanceof List : "report.assertions is not a list"

// Verify assertion results — both reachable assertions should pass
def reportAssertions = report.assertions.collectEntries { [it.name, it.pass] }
println "Report assertions: ${reportAssertions}"

assert reportAssertions.containsKey("level-1") :
    "assertion 'level-1' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["level-1"] == true :
    "assertion 'level-1' should pass, got: ${reportAssertions['level-1']}"

assert reportAssertions.containsKey("level-2") :
    "assertion 'level-2' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["level-2"] == true :
    "assertion 'level-2' should pass, got: ${reportAssertions['level-2']}"

// Verify built-in lifecycle assertions
assert reportAssertions.containsKey("simulation started") :
    "built-in assertion 'simulation started' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["simulation started"] == true :
    "assertion 'simulation started' should pass, got: ${reportAssertions['simulation started']}"

assert reportAssertions.containsKey("simulation stopped successfully") :
    "built-in assertion 'simulation stopped successfully' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["simulation stopped successfully"] == false :
    "assertion 'simulation stopped successfully' should fail (TraceAuditor found the bug), got: ${reportAssertions['simulation stopped successfully']}"

// Verify determinism via report: the Simulator detects hash mismatches on replay and exits
// with reason "flaky", which appears as a failExample on the "simulation stopped successfully"
// assertion. No such failures should exist.
// Note: we don't assert that replays actually occurred (replay probability is low at 1%),
// because determinism is systematically verified during explore runs.
def stoppedAssertion = report.assertions.find { it.name == "simulation stopped successfully" }
def flakyFailures = stoppedAssertion?.examples?.failExamples?.findAll { it.details?.reason == "flaky" }
assert flakyFailures == null || flakyFailures.size() == 0 :
    "Non-determinism detected: ${flakyFailures.size()} replay(s) produced different hashes"

println "All verifications passed — testapp JAR correctly detects bug via TraceAuditor and reports failure."
