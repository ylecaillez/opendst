def targetDir = new File(basedir, "target/opendst")
File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

// Class selection checks
if (!new File(targetDir, "com.pingidentity.opendst.it.AlphaDST/report.html").exists()) throw new RuntimeException("AlphaDST missing")
if (!new File(targetDir, "com.pingidentity.opendst.it.BetaDST/report.html").exists()) throw new RuntimeException("BetaDST missing")
if (new File(targetDir, "com.pingidentity.opendst.it.GammaDST/report.html").exists()) throw new RuntimeException("GammaDST should not be here")

// Method selection checks
if (!logContent.contains("OpenDST runs com.pingidentity.opendst.it.MultiMethodDST#firstMethod")) {
    throw new RuntimeException("MultiMethodDST#firstMethod should have been run")
}

if (!logContent.contains("OpenDST runs com.pingidentity.opendst.it.MultiMethodDST#secondMethod")) {
    throw new RuntimeException("MultiMethodDST#secondMethod should have been run")
}

if (!logContent.contains("OpenDST runs com.pingidentity.opendst.it.AnotherDST#run")) {
    throw new RuntimeException("AnotherDST#run should have been run")
}

if (logContent.contains("OpenDST runs com.pingidentity.opendst.it.MultiMethodDST#otherTask")) {
    throw new RuntimeException("MultiMethodDST#otherTask should NOT have been run")
}

return true
