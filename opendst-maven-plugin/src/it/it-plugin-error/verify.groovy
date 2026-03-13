File logFile = new File(basedir, "build.log")
assert logFile.exists()
def logContent = logFile.text

assert logContent.contains("Invalid OpenDST assertion")
assert logContent.contains("message must be a string literal")
assert logContent.contains("Assert.reachable")
