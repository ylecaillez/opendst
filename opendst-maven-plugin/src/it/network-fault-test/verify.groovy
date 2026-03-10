import groovy.json.JsonSlurper

// Verify the build mojo produced a self-contained JAR for the network-fault-test,
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

    // Check Bootstrap.class is at JAR root
    check(jar.getEntry("com/pingidentity/opendst/runner/Bootstrap.class") != null, "Bootstrap.class missing at JAR root", logFile)

    // Check runner and library JARs in system/
    check(jar.getEntry("system/opendst-runner.jar") != null, "opendst-runner.jar missing from system/", logFile)
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
    def assertionsJson = jar.getInputStream(assertionsEntry).text
    def assertions = new JsonSlurper().parseText(assertionsJson)
    check(assertions instanceof List, "assertions.json is not a list", logFile)

    def labels = assertions.collect { it.message }
    println "Assertions found: ${labels}"

    // Core reachable assertions that must always be present
    def requiredAssertions = [
        "server-restart",
        "server-deferred-bind",
        "server-constructor-bind",
        "server-normal-echo",
        "server-output-halfclose",
        "server-input-halfclose",
        "server-random-sequence",
        "client-direct-connect",
        "client-unbound",
        "client-open",
    ]
    for (a in requiredAssertions) {
        check(labels.contains(a), "assertion '${a}' not found in assertions.json: ${labels}", logFile)
    }

    // Verify build-config.json contains expected configuration
    def buildConfigEntry = jar.getEntry("build-config.json")
    def buildConfigJson = jar.getInputStream(buildConfigEntry).text
    def buildConfig = new JsonSlurper().parseText(buildConfigJson)
    check(buildConfig.faults.network.enabled == true, "Network faults should be enabled", logFile)
} finally {
    jar.close()
}

// ---- Phase 2: Run the built JAR and verify the simulation report ----

def reportDir = new File(basedir, "target/opendst-report")
reportDir.mkdirs()

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

println "Running: ${javaBin} -jar ${jarFile.absolutePath} --report-dir ${reportDir.absolutePath}"

// Stagnation limit: use invoker property if set (e.g., by deep profile), else default to 50
def stagnationLimit = System.getProperty("opendst.it.stagnationLimit") ?: "50"
println "Using stagnation limit: ${stagnationLimit}"

def process = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                                 "--report-dir", reportDir.absolutePath,
                                 "--stagnation-limit", stagnationLimit)
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

// Core reachable assertions should pass (these are always reached due to randomness + sufficient iterations)
def mustPass = [
    "server-restart",
    "server-deferred-bind",
    "server-constructor-bind",
    "client-direct-connect",
    "client-unbound",
    "client-open",
]
for (a in mustPass) {
    assert reportAssertions.containsKey(a) :
        "assertion '${a}' not found in report: ${reportAssertions.keySet()}"
    assert reportAssertions[a] == "pass" :
        "assertion '${a}' should pass, got: ${reportAssertions[a]}"
}

println "All verifications passed — network-fault-test JAR runs successfully and produces correct report."
