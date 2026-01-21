import groovy.json.JsonSlurper

File logFile = new File(basedir, "build.log")
assert logFile.exists()
def logContent = logFile.text

// 1. AlwaysNeverHitDST failed
// 2. SometimesSatisfiedDST succeeded because eventually condition was true
assert logContent.contains("signal type:found")
assert logContent.contains("label:probabilistic-liveness")

// 3. LivenessViolationDST failed
assert logContent.contains("OpenDST found at least one failure. See reports for details.")

// Verify JSON report content for SometimesSatisfiedDST
// sometimesReportFile should pass with higher stagnation limit
def sometimesReportFile = new File(basedir, "target/opendst/com.pingidentity.opendst.it.SometimesSatisfiedDST/report.json")
assert sometimesReportFile.exists()
def sometimesReport = new JsonSlurper().parse(sometimesReportFile)
assert sometimesReport.failed == false

def probLiveness = sometimesReport.properties.find { it.label == "probabilistic-liveness" }
assert probLiveness : "Property 'probabilistic-liveness' not found in report"
assert probLiveness.status == "pass"

// Verify JSON report content for AlwaysNeverHitDST (should fail)
def alwaysReportFile = new File(basedir, "target/opendst/com.pingidentity.opendst.it.AlwaysNeverHitDST/report.json")
assert alwaysReportFile.exists()
def alwaysReport = new JsonSlurper().parse(alwaysReportFile)
assert alwaysReport.failed == true

def alwaysNeverHit = alwaysReport.properties.find { it.label == "dead-code-assertion" }
assert alwaysNeverHit : "Property 'dead-code-assertion' not found in report"
assert alwaysNeverHit.status == "fail"

// Verify JSON report content for LivenessViolationDST (should fail)
def livenessReportFile = new File(basedir, "target/opendst/com.pingidentity.opendst.it.LivenessViolationDST/report.json")
assert livenessReportFile.exists()
def livenessReport = new JsonSlurper().parse(livenessReportFile)
assert livenessReport.failed == true

def impossibleLiveness = livenessReport.properties.find { it.label == "impossible-liveness" }
assert impossibleLiveness : "Property 'impossible-liveness' not found in report"
assert impossibleLiveness.status == "fail"

// Verify JSON report content for ComparativeAssertionDST (should pass)
def comparativeReportFile = new File(basedir, "target/opendst/com.pingidentity.opendst.it.ComparativeAssertionDST/report.json")
assert comparativeReportFile.exists()
def comparativeReport = new JsonSlurper().parse(comparativeReportFile)
assert comparativeReport.failed == false

def comparativeSafety = comparativeReport.properties.find { it.label == "comparative-safety" }
assert comparativeSafety : "Property 'comparative-safety' not found in report"
assert comparativeSafety.status == "pass"
