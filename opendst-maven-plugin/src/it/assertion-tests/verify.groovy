import groovy.json.JsonSlurper

// Verify the build mojo produced a self-contained JAR with all assertion types,
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

// Verify assertions.json contains the expected assertions from all 4 apps
import java.util.jar.JarFile
def jar = new JarFile(jarFile)
try {
    def assertionsEntry = jar.getEntry("META-INF/opendst/assertions.json")
    check(assertionsEntry != null, "assertions.json missing from JAR", logFile)

    def assertionsJson = jar.getInputStream(assertionsEntry).text
    def assertions = new JsonSlurper().parseText(assertionsJson)
    check(assertions instanceof List, "assertions.json is not a list", logFile)

    def labels = assertions.collect { it.message }
    check(labels.contains("dead-code-assertion"),
          "assertion 'dead-code-assertion' not found in assertions.json: " + labels, logFile)
    check(labels.contains("comparative-safety"),
          "assertion 'comparative-safety' not found in assertions.json: " + labels, logFile)
    check(labels.contains("impossible-liveness"),
          "assertion 'impossible-liveness' not found in assertions.json: " + labels, logFile)
    check(labels.contains("probabilistic-liveness"),
          "assertion 'probabilistic-liveness' not found in assertions.json: " + labels, logFile)

    // Verify build-config.json
    def buildConfigEntry = jar.getEntry("build-config.json")
    check(buildConfigEntry != null, "build-config.json missing from JAR", logFile)
    def buildConfigJson = jar.getInputStream(buildConfigEntry).text
    def buildConfig = new JsonSlurper().parseText(buildConfigJson)
    check(buildConfig.parallelism == 1, "Parallelism should be 1, got: " + buildConfig.parallelism, logFile)
    check(buildConfig.stagnationLimit == 10, "StagnationLimit should be 10, got: " + buildConfig.stagnationLimit, logFile)
    check(buildConfig.duration == 1000, "Duration should be 1000, got: " + buildConfig.duration, logFile)
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
                                 "--report-dir", reportDir.absolutePath)
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
def reportAssertions = report.assertions.collectEntries { [it.name, it] }
println "Report assertions: ${reportAssertions.collectEntries { k, v -> [k, v.pass] }}"

// dead-code-assertion: always(true) in dead code — never hit → fail
// An ALWAYS assertion (mustHit=true) that is never executed is reported as fail
// because it must be reached at least once to pass.
assert reportAssertions.containsKey("dead-code-assertion") :
    "assertion 'dead-code-assertion' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["dead-code-assertion"].pass == "fail" :
    "assertion 'dead-code-assertion' should fail (never hit, mustHit=true), got: ${reportAssertions['dead-code-assertion'].pass}"

// comparative-safety: alwaysGreaterThan(value, -1) where value >= 0 — always passes
assert reportAssertions.containsKey("comparative-safety") :
    "assertion 'comparative-safety' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["comparative-safety"].pass == "pass" :
    "assertion 'comparative-safety' should pass, got: ${reportAssertions['comparative-safety'].pass}"

// impossible-liveness: sometimes(false) — never satisfied, should fail
assert reportAssertions.containsKey("impossible-liveness") :
    "assertion 'impossible-liveness' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["impossible-liveness"].pass == "fail" :
    "assertion 'impossible-liveness' should fail (never satisfied), got: ${reportAssertions['impossible-liveness'].pass}"

// probabilistic-liveness: sometimes(random boolean) — should pass with overwhelming probability
assert reportAssertions.containsKey("probabilistic-liveness") :
    "assertion 'probabilistic-liveness' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["probabilistic-liveness"].pass == "pass" :
    "assertion 'probabilistic-liveness' should pass, got: ${reportAssertions['probabilistic-liveness'].pass}"

println "All verifications passed — build JAR runs successfully and produces correct report."
