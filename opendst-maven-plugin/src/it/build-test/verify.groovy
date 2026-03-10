import groovy.json.JsonSlurper

// Verify the build mojo produced a self-contained JAR, then run it and verify the simulation report.
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

    // Check Bootstrap.class is at JAR root (the only class needed for java -jar bootstrap)
    check(jar.getEntry("com/pingidentity/opendst/runner/Bootstrap.class") != null, "Bootstrap.class missing at JAR root", logFile)

    // Check runner JAR in system/ (BuildRunner, DeploymentRunner, etc. — loaded by Bootstrap's URLClassLoader)
    check(jar.getEntry("system/opendst-runner.jar") != null, "opendst-runner.jar missing from system/", logFile)

    // Check library JARs in system/ (Jackson, SnakeYAML — loaded by Bootstrap's URLClassLoader)
    check(jar.getEntry("system/jackson-databind.jar") != null, "jackson-databind.jar missing from system/", logFile)
    check(jar.getEntry("system/jackson-core.jar") != null, "jackson-core.jar missing from system/", logFile)
    check(jar.getEntry("system/snakeyaml-engine.jar") != null, "snakeyaml-engine.jar missing from system/", logFile)

    // Check apps/ has instrumented application content
    def hasApps = jar.entries().any { it.name.startsWith("apps/") }
    check(hasApps, "No application content in apps/", logFile)

    // Check that instrumented classes.jar exists
    def hasClassesJar = jar.entries().any { it.name.contains("WEB-INF/classes.jar") }
    check(hasClassesJar, "No WEB-INF/classes.jar in apps/", logFile)

    // Verify assertions.json contains the expected assertions
    def assertionsEntry = jar.getEntry("META-INF/opendst/assertions.json")
    check(assertionsEntry != null, "assertions.json missing from JAR", logFile)

    def assertionsJson = jar.getInputStream(assertionsEntry).text
    def assertions = new JsonSlurper().parseText(assertionsJson)
    check(assertions instanceof List, "assertions.json is not a list", logFile)

    def labels = assertions.collect { it.message }
    check(labels.contains("server-echo"), "assertion 'server-echo' not found in assertions.json: " + labels, logFile)
    check(labels.contains("client-received"), "assertion 'client-received' not found in assertions.json: " + labels, logFile)
    check(labels.contains("client-done"), "assertion 'client-done' not found in assertions.json: " + labels, logFile)
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

// Verify assertion results — all reachable assertions should pass
def reportAssertions = report.assertions.collectEntries { [it.name, it.pass] }
println "Report assertions: ${reportAssertions}"

assert reportAssertions.containsKey("server-echo") :
    "assertion 'server-echo' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["server-echo"] == "pass" :
    "assertion 'server-echo' should pass, got: ${reportAssertions['server-echo']}"

assert reportAssertions.containsKey("client-received") :
    "assertion 'client-received' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["client-received"] == "pass" :
    "assertion 'client-received' should pass, got: ${reportAssertions['client-received']}"

assert reportAssertions.containsKey("client-done") :
    "assertion 'client-done' not found in report: ${reportAssertions.keySet()}"
assert reportAssertions["client-done"] == "pass" :
    "assertion 'client-done' should pass, got: ${reportAssertions['client-done']}"

println "All verifications passed — build JAR runs successfully and produces correct report."
