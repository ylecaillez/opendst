import groovy.json.JsonSlurper

// Check opendst can find the bug using signals
File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

// Helper to avoid Power Assert dumping logContent
def check(boolean condition, String message, File file) {
    if (!condition) {
        println "Verification failed: ${message}"
        // Only dump last 100 lines to avoid too much noise
        def lines = file.readLines()
        def start = Math.max(0, lines.size() - 100)
        println "--- build.log tail ---"
        lines[start..-1].each { println it }
        println "----------------------"
        println "--- Full build.log ---\n" + file.text; assert false : message
    }
}

check(logContent.contains("Instrumenting"), "OpenDST instrumentation was not found", logFile)

// Check for runs in either build 1 or build 2
check(logContent.contains("runs com.pingidentity.opendst.testapp.SecretSequenceDST#run"), "SecretSequenceDST has not been run", logFile)
check(logContent.contains("runs com.pingidentity.opendst.testapp.FlakyDST#run"), "NonDeterministicDST has not been run", logFile)
check(logContent.contains("runs com.pingidentity.opendst.testapp.FileSystemFaultInjectionDST#run"), "FileSystemFaultInjectionDST has not been run", logFile)
check(logContent.contains("runs com.pingidentity.opendst.testapp.NetworkFaultInjectionDST#run"), "NetworkFaultInjectionDST has not been run", logFile)
check(logContent.contains("runs com.pingidentity.opendst.testapp.InstrumentationDST#run"), "InstrumentationDST has not been run", logFile)

check(logContent.contains("run type:random-walk"), "No random-walk run", logFile)
check(logContent.contains("run type:explore"), "No explore run", logFile)
check(logContent.contains("run type:check"), "No check run", logFile)
check(logContent.contains("run type:verified"), "No verified run", logFile)
check(logContent.contains("run type:flaky"), "No flaky run", logFile)

check(logContent.contains("signal type:found"), "No signal found", logFile)

check(!logContent.contains("Stagnation limit reached"), "Stagnation limit reached - this should not happen in tests", logFile)

// Check for specific success strings in the failure causes
check(logContent.contains("OpenDST: filesystem-fault"), "OpenDST filesystem-fault was not found", logFile)
check(logContent.contains("OpenDST: network-partition-fault"), "OpenDST network-partition-fault was not found", logFile)
check(logContent.contains("OpenDST: bug-discovered"), "OpenDST bug-discovered was not found", logFile)

// Check for Replay
// check(logContent.contains("OpenDST replays com.pingidentity.opendst.testapp.FlakyDST#run"), "Replay of FlakyDST was not found", logFile)

// Verify log spy
File spyFile = new File(basedir, "target/opendst/com.pingidentity.opendst.testapp.NetworkFaultInjectionDST/runs/run-0/simulator.log")
assert spyFile.exists() : "Log spy file was not found at ${spyFile.absolutePath}"
assert spyFile.length() > 0 : "Log spy file is empty"

// Verify JSON Report for SecretSequenceDST
File ssReportFile = new File(basedir, "target/opendst/com.pingidentity.opendst.testapp.SecretSequenceDST/report.json")
assert ssReportFile.exists() : "SecretSequenceDST JSON report not found"
def ssReport = new JsonSlurper().parse(ssReportFile)
// SecretSequenceDST throws AssertionError, so status should be FAIL
assert ssReport.failed == true : "SecretSequenceDST should have failed"

// Verify JSON Report for NetworkFaultInjectionDST
File netReportFile = new File(basedir, "target/opendst/com.pingidentity.opendst.testapp.NetworkFaultInjectionDST/report.json")
assert netReportFile.exists() : "NetworkFaultInjectionDST JSON report not found"
def netReport = new JsonSlurper().parse(netReportFile)
assert netReport.failed == true : "NetworkFaultInjectionDST should have failed"

