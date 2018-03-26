package bcgov

import org.kohsuke.github.*
import org.jenkinsci.plugins.workflow.cps.CpsScript
import com.cloudbees.jenkins.GitHubRepositoryName

/*
* Reference:
*   - http://github-api.kohsuke.org/apidocs/index.html
*   - https://github.com/jenkinsci/github-plugin/blob/master/src/main/java/com/cloudbees/jenkins/GitHubRepositoryName.java
* */
class GitHubHelper {
    static GHRepository getGitHubRepository(CpsScript script){
        return getGitHubRepository(script.scm.getUserRemoteConfigs()[0].getUrl())
    }
    static GHRepository getGitHubRepository(String url){
        return GitHubRepositoryName.resolveOne(url)
    }

    /*
    * http://github-api.kohsuke.org/apidocs/org/kohsuke/github/GHDeploymentBuilder.html
    * */
    long createDeployment(CpsScript script, Map deploymentConfig) {
        def ghRepo=getGitHubRepository(script)
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
        def ghRepo=getGitHubRepository(script)
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