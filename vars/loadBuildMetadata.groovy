
def listModules(workspaceDir) {
    def modules=[:];
    for (def file:new File(workspaceDir).listFiles()){
        if (file.isDirectory() && !file.getName().startsWith('.')) {
            def module = ['name':file.getName()]
            def commitId=null; //"git rev-list -1 HEAD -- '${file.name}'".execute(null, new File(workspaceDir)).text
            commitId=sh(returnStdout: true, script: "#!/bin/sh -e\n git rev-list -1 HEAD -- '${file.name}'").trim()
            module['commit']="${commitId}"
            modules[file.getName()] = module;
        }
    }
    return modules;
}

def call(metadata) {
    //metadata.modules=listModules(pwd())
    //metadata.commit = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    metadata.isPullRequest=(env.CHANGE_ID != null && env.CHANGE_ID.trim().length()>0)
    metadata.gitRepoUrl = scm.getUserRemoteConfigs()[0].getUrl()

    metadata.buildBranchName = env.BRANCH_NAME;
    metadata.buildEnvName = 'bld'
    metadata.buildNamePrefix = "${metadata.appName}"

    
    if (metadata.isPullRequest){
        metadata.pullRequestNumber=env.CHANGE_ID
        metadata.gitBranchRemoteRef="refs/pull/${metadata.pullRequestNumber}/head";
        metadata.buildEnvName="pr-${metadata.pullRequestNumber}"      
    }
  
    metadata.buildNameSuffix = "-${metadata.buildEnvName}"
    return metadata;
}
