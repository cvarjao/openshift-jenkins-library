
def call() {
    while(currentBuild.rawBuild.getPreviousBuildInProgress() != null) {
        currentBuild.rawBuild.getPreviousBuildInProgress().doKill()
    }
}
