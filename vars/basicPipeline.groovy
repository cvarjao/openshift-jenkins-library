
/*
* References:
* - https://zwischenzugs.com/2017/04/23/things-i-wish-i-knew-before-using-jenkins-pipelines/
* - https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
* - https://jenkins.io/doc/pipeline/examples/
*/

import hudson.model.Result;
import jenkins.model.CauseOfInterruption.UserInterruption;
import org.kohsuke.github.*
import bcgov.OpenShiftHelper
import bcgov.GitHubHelper


def call(body) {
    def context= [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = context
    body()

    //def metadata=['appName':context.name]

    properties([
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '20'))
    ])

    stage('Prepare') {
        abortAllPreviousBuildInProgress(currentBuild)
        echo "BRANCH_NAME=${env.BRANCH_NAME}\nCHANGE_ID=${env.CHANGE_ID}\nCHANGE_TARGET=${env.CHANGE_TARGET}\nBUILD_URL=${env.BUILD_URL}"
    }
    stage('Build') {
        node('master') {
            checkout scm
            def ghDeploymentId = new GitHubHelper().createDeployment(this, GitHubHelper.getPullRequest(this).getHead().getSha(), ['environment':'build'])
            //GitHubHelper.getPullRequest(this).comment("Build in progress")
            new GitHubHelper().createDeploymentStatus(this, ghDeploymentId, 'SUCCESS', [:])

            loadBuildMetadata(context)
            //echo "metadata:\n${metadata}"
            def stashIncludes=[]
            for ( def templateCfg : context.bcModels + context.dcModels){
                if ('-f'.equalsIgnoreCase(templateCfg[0])){
                    stashIncludes.add(templateCfg[1])
                }
            }
            stash(name: 'openshift', includes:stashIncludes.join(','))
            echo 'Building ...'
            unstash(name: 'openshift')
            new OpenShiftHelper().build(this, context)

            //GitHubHelper.getPullRequest(this).comment("Build complete")
        }
    }
    for(String envKeyName: context.env.keySet() as String[]){
        stageDeployName=envKeyName.toUpperCase()
        if (!"DEV".equalsIgnoreCase(stageDeployName) && "master".equalsIgnoreCase(env.CHANGE_TARGET)){
            stage("Check - ${stageDeployName}") {
                node('master') {
                    waitUntil {
                        try {
                            //do something
                            unstash(name: 'openshift')
                            return true
                        } catch (ex) {
                            input "Retry Validation?"
                            return false
                        }
                    }
                }
            }
            stage("Approve - ${stageDeployName}") {
                input id: "deploy_${stageDeployName.toLowerCase()}", message: "Deploy to ${stageDeployName}?", ok: 'Approve', submitterParameter: 'approved_by'
            }
        }

        if ("DEV".equalsIgnoreCase(stageDeployName) || "master".equalsIgnoreCase(env.CHANGE_TARGET)){
            stage("Deploy - ${stageDeployName}") {
                node('master') {
                    String envName = stageDeployName.toLowerCase()
                    if ("DEV".equalsIgnoreCase(stageDeployName)) {
                        envName = "dev-pr-${env.CHANGE_ID}"
                    }
                    echo "Deploying to ${stageDeployName} as ${envName}"
                    //long ghDeploymentId = GitHubHelper.createDeployment(this, metadata.commit, ['environment':envName])
                    context['deploy'] = [
                            'envName':envName,
                            'projectName':context.env[envKeyName].project,
                            'envKeyName':envKeyName
                    ]

                    //GitHubHelper.getPullRequest(this).comment("Deploying to DEV")
                    unstash(name: 'openshift')
                    new OpenShiftHelper().deploy(this, context)
                    context.remove('deploy')
                    //GitHubHelper.createDeploymentStatus(this, ghDeploymentId, GHDeploymentState.SUCCESS).create()
                    //GitHubHelper.getPullRequest(this).comment("Deployed to DEV")
                }
            }
        }

    }
    stage('Cleanup') { }

}
