// Verify the --plan replay feature: build the JAR, run it once to produce a plan,
// then replay the plan and verify that the replay completes successfully.

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

def javaHome = System.getProperty("java.home")
def javaBin = new File(javaHome, "bin/java").absolutePath

// ---- Phase 1: Run the JAR to produce a plan file ----

def workingDir = new File(basedir, "target/replay-work")

println "Phase 1: Running simulation to produce a plan file..."
def p1 = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                             "--working-dir", workingDir.absolutePath,
                             "--stagnation-limit", "5")
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

def p1Output = new StringBuilder()
p1.inputStream.eachLine { line ->
    p1Output.append(line).append("\n")
    println "[Phase1] ${line}"
}

def p1Exit = p1.waitFor()
check(p1Exit == 0, "Phase 1 simulation failed with exit code ${p1Exit}", logFile)

// Find a plan file in report/plans/
def plansDir = new File(workingDir, "report/plans")
check(plansDir.exists() && plansDir.isDirectory(), "Plans directory does not exist: ${plansDir.absolutePath}", logFile)

def planFiles = plansDir.listFiles({ dir, name -> name.endsWith(".plan.json") } as FilenameFilter)
check(planFiles != null && planFiles.length > 0,
      "No plan files found in ${plansDir.absolutePath}", logFile)

// Pick the first plan file
File planFile = planFiles[0]
println "Found plan file: ${planFile.name} (${planFile.length()} bytes)"

// ---- Phase 2: Replay the plan file using --plan ----

def replayWorkingDir = new File(basedir, "target/replay-verify-work")

println "Phase 2: Replaying plan ${planFile.name}..."
def p2 = new ProcessBuilder(javaBin, "-jar", jarFile.absolutePath,
                             "--working-dir", replayWorkingDir.absolutePath,
                             "--plan", planFile.absolutePath)
        .directory(basedir)
        .redirectErrorStream(true)
        .start()

def p2Output = new StringBuilder()
p2.inputStream.eachLine { line ->
    p2Output.append(line).append("\n")
    println "[Phase2] ${line}"
}

def p2Exit = p2.waitFor()

// Verify replay succeeded
check(p2Exit == 0, "Replay failed with exit code ${p2Exit}", logFile)
check(p2Output.toString().contains("Replay complete."),
      "Replay output does not contain 'Replay complete.'", logFile)

println "All verifications passed — it-determinism covers replay feature."
