
def call(_openshift, baseSelector, commitId) {
  String buildNameSelector=null;
  
    def buildSelector = _openshift.selector( 'builds', baseSelector + ['commit-id':"${commitId}"]);
    if (buildSelector.count()==0){
        echo "Starting new build for '${baseSelector}'"
        buildSelector = _openshift.selector( 'bc', baseSelector).startBuild("--commit=${commitId}")
        buildSelector.label(['commit-id':"${gitAppCommitId}"], "--overwrite")
        buildNameSelector=buildSelector.name()
        echo "New build started - ${buildSelector.name()}"
        /*
        buildSelector.logs('-f');        
        openshift.selector("${buildSelector.name()}").watch {
            def build=it.object();
            return !"Running".equalsIgnoreCase(build.status.phase)
        }
        def build=openshift.selector("${buildSelector.name()}").object();
        if (!"Complete".equalsIgnoreCase(build.status.phase)){
            error "Build '${buildSelector.name()}' did not successfully complete (${build.status.phase})"
        }
        echo "OutputImageDigest: '${build.status.output.to.imageDigest}'"
        echo "outputDockerImageReference: '${build.status.outputDockerImageReference}'"
        */
    }else{
      buildNameSelector=buildSelector.name()
      echo "Skipping new build. Reusing '${buildNameSelector}'"
      //def build=buildSelector.object()
      //echo "OutputImageDigest: '${build.status.output.to.imageDigest}'"
      //echo "outputDockerImageReference: '${build.status.outputDockerImageReference}'"
    }
    return buildNameSelector;
}
