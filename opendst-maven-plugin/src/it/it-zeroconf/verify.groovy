import groovy.json.JsonSlurper
import java.util.jar.JarFile

// Verify zero-config mode: no deployment.yaml is present; the build plugin discovers
// EasyApp automatically and produces a self-contained JAR with correct deployment.json.
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
check(jarFiles != null && jarFiles.length == 1,
      "Expected exactly one *-opendst.jar in target/, found: ${jarFiles?.length ?: 0}", logFile)
File jarFile = jarFiles[0]
check(jarFile.length() > 0, "${jarFile.name} is empty", logFile)

// ---- Verify deployment.json from within the produced JAR ----

def jar = new JarFile(jarFile)
def deploymentJson
try {
    def deploymentEntry = jar.getEntry("META-INF/opendst/deployment.json")
    check(deploymentEntry != null, "META-INF/opendst/deployment.json missing from JAR", logFile)
    deploymentJson = new JsonSlurper().parseText(jar.getInputStream(deploymentEntry).text)
} finally {
    jar.close()
}

// Assert exactly one service
def services = deploymentJson.services
check(services != null, "deployment.json has no 'services' field", logFile)
check(services instanceof Map, "deployment.json 'services' is not an object", logFile)
check(services.size() == 1, "Expected exactly 1 service, found: ${services.size()}", logFile)

// Assert service name is the hostname derived from 'EasyApp'
def serviceName = services.keySet().first()
check(serviceName == "easy-app",
      "Expected service name 'easy-app' (hostname of EasyApp), got: '${serviceName}'", logFile)

def svc = services[serviceName]
check(svc.className == "com.pingidentity.opendst.it.zeroconf.EasyApp",
      "Expected className 'com.pingidentity.opendst.it.zeroconf.EasyApp', got: '${svc.className}'", logFile)
check(svc.ip == "10.0.0.1",
      "Expected ip '10.0.0.1', got: '${svc.ip}'", logFile)
check(svc.args == [],
      "Expected args [], got: ${svc.args}", logFile)
check(svc.dir == "it-zeroconf",
      "Expected dir 'it-zeroconf' (artifactId), got: '${svc.dir}'", logFile)

// Assert no traceAuditor entry (EasyApp does not implement TraceAuditor)
check(deploymentJson.traceAuditor == null,
      "Expected no traceAuditor entry, got: ${deploymentJson.traceAuditor}", logFile)

println "deployment.json assertions passed."

// ---- Run the built JAR and verify exit code 0 ----

def workingDir = new File(basedir, "target/opendst-work")

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

println "Running: ${javaBin} -jar ${jarFile.absolutePath} --working-dir ${workingDir.absolutePath}"

def process = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                                 "--working-dir", workingDir.absolutePath,
                                 "--stagnation-limit", "5",
                                 "--stop", "any-fail")
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

process.inputStream.eachLine { line -> println "[JAR] ${line}" }

def exitCode = process.waitFor()
check(exitCode == 0, "Expected exit code 0 from java -jar, got: ${exitCode}", logFile)

println "All verifications passed — zero-config mode produces a correct JAR and the simulation completes successfully."
