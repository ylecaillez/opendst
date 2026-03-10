import groovy.json.JsonSlurper

// Verify the build mojo produced a self-contained JAR with valid assertions,
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

// Verify assertions.json contains the expected assertions
import java.util.jar.JarFile
def jar = new JarFile(jarFile)
try {
    def assertionsEntry = jar.getEntry("META-INF/opendst/assertions.json")
    check(assertionsEntry != null, "assertions.json missing from JAR", logFile)

    def assertionsJson = jar.getInputStream(assertionsEntry).text
    def assertions = new JsonSlurper().parseText(assertionsJson)
    check(assertions instanceof List, "assertions.json is not a list", logFile)

    def labels = assertions.collect { it.message }
    check(labels.contains("level-1"), "assertion 'level-1' not found in assertions.json: " + labels, logFile)
    check(labels.contains("invariant"), "assertion 'invariant' not found in assertions.json: " + labels, logFile)
} finally {
    jar.close()
}

// ---- Phase 2: Run the built JAR and verify the simulation report ----

def reportDir = new File(basedir, "target/opendst-report")
reportDir.mkdirs()

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

println "Running: ${javaBin} -jar ${jarFile.absolutePath} --report-dir ${reportDir.absolutePath}"

def process = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                                 "--report-dir", reportDir.absolutePath,
                                 "--stagnation-limit", "1",
                                 "--duration", "5000")
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

if (exitCode != 0) {
    println "JAR execution output:"
    println output.toString()
    assert false : "java -jar ${jarFile.name} failed with exit code ${exitCode}"
}

// Verify the report was produced
def reportFile = new File(reportDir, "report.json")
assert reportFile.exists() : "report.json was not created in ${reportDir.absolutePath}"
assert reportFile.length() > 0 : "report.json is empty"

def report = new JsonSlurper().parseText(reportFile.text)

// Verify report structure
assert report.count != null : "report.count is missing"
assert report.count > 0 : "report.count should be > 0, got: ${report.count}"
assert report.duration != null : "report.duration is missing"
assert report.assertions != null : "report.assertions is missing"
assert report.assertions instanceof List : "report.assertions is not a list"

// Verify assertion results
def reportAssertions = report.assertions.collectEntries { [it.name, it.pass] }
println "Report assertions: ${reportAssertions}"

assert reportAssertions.containsKey("level-1") :
    "assertion 'level-1' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions.containsKey("invariant") :
    "assertion 'invariant' not found in report: ${reportAssertions.keySet()}"

// level-1 is a reachable assertion — it should pass since ValidAssertionApp always hits it
assert reportAssertions["level-1"] == "pass" :
    "assertion 'level-1' should pass, got: ${reportAssertions['level-1']}"

// invariant is always(true, ...) — should pass
assert reportAssertions["invariant"] == "pass" :
    "assertion 'invariant' should pass, got: ${reportAssertions['invariant']}"

println "All verifications passed — build JAR runs successfully and produces correct report."
