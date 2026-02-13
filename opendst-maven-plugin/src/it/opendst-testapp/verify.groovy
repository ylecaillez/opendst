// Check opendst can find the bug
File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"
def logContent = logFile.text

assert logContent.contains("OpenDST runs") : "Test has not been done"
assert logContent.contains("OpenDST replays") : "Replay has not been done"

def matchingLines = logContent.readLines().findAll { it.contains("Bug detected by the log-processor") }
assert matchingLines.size() == 2 : "The string 'Bug reached!' was not found on exactly two different lines"
