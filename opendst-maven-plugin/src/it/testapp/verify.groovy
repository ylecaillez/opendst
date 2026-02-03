// 1. Check if the build.log file exists
File logFile = new File(basedir, "build.log")
assert logFile.exists() : "The build.log file was not found!"

def logContent = logFile.text
assert logContent.contains("The failure pattern 'Goal Reached!' has been detected !") : "The bug has not been found"
