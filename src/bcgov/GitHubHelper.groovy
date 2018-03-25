package bcgov

import org.kohsuke.github.*;
import org.jenkinsci.plugins.workflow.cps.CpsScript;

class GitHubHelper {
    def getGitHubRepository(){
        def gitRepoUrl = scm.getUserRemoteConfigs()[0].getUrl()
        def gitRepoFullName=gitRepoUrl.replace('https://github.com/', '').replace('.git', '')
        //echo "gitRepoFullName='${gitRepoFullName}'"

        def githubUsername=env.GH_USERNAME
        def githubPassword = env.GH_PASSWORD
        def github=new GitHubBuilder().withPassword(githubUsername, githubPassword).build()
        return github.getRepository(gitRepoFullName)
    }

    /*
    * http://github-api.kohsuke.org/apidocs/org/kohsuke/github/GHDeploymentBuilder.html
    * */
    long createDeployment(CpsScript script, Map deploymentConfig) {
        def ghRepo=getGitHubRepository(script.scm.getUserRemoteConfigs()[0].getUrl())
        def ghDeploymentResponse=ghRepo.createDeployment(deploymentConfig.ref).environment(deploymentConfig.environment)

        if (deploymentConfig.payload){
            ghDeploymentResponse.payload(deploymentConfig.payload)
        }

        if (deploymentConfig.description){
            ghDeploymentResponse.description(deploymentConfig.description)
        }

        if (deploymentConfig.task){
            ghDeploymentResponse.task(deploymentConfig.task)
        }

        if (deploymentConfig.requiredContexts){
            ghDeploymentResponse.requiredContexts(deploymentConfig.requiredContexts)
        }

        return ghDeploymentResponse.create().getId()
    }

    long updateDeploymentStatus(CpsScript script, long deploymentId, String statusName, Map deploymentStatusConfig) {
        def ghRepo=getGitHubRepository(script.scm.getUserRemoteConfigs()[0].getUrl())
        def ghDeploymentState=GHDeploymentState.valueOf(statusName)

        def ghDeploymentStatus=ghRepo.getDeployment(deploymentId).createStatus(ghDeploymentState)

        if (deploymentStatusConfig.description){
            ghDeploymentStatus.description(deploymentStatusConfig.description)
        }
        if (deploymentStatusConfig.targetUrl){
            ghDeploymentStatus.targetUrl(deploymentStatusConfig.targetUrl)
        }
        return ghDeploymentStatus.create().getId()
    }
}