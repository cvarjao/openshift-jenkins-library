
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


    properties([
            buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '20'))
    ])

    stage('Prepare') {
        abortAllPreviousBuildInProgress(currentBuild)
        echo "BRANCH_NAME=${env.BRANCH_NAME}\nCHANGE_ID=${env.CHANGE_ID}\nCHANGE_TARGET=${env.CHANGE_TARGET}\nBUILD_URL=${env.BUILD_URL}"
        def pullRequest=GitHubHelper.getPullRequest(this)
        echo "Pull-Request: ${pullRequest}"
        echo "Pull-Request: head.ref: ${pullRequest.getHead().getRef()}"
    }

    stage('Cleanup') {
        def inputResponse=input(id: 'close_pr', message: "Ready to Accept/Merge, and Close pull-request #${env.CHANGE_ID}?", ok: 'Yes', submitter: 'authenticated', submitterParameter: 'approver')
        script.input "Merge, and Close PR?"
        GitHubHelper.mergeAndClosePullRequest(this)
    }

    stage('Build') {
        node('master') {
            checkout scm
            new OpenShiftHelper().build(this, context)
            if ("master".equalsIgnoreCase(env.CHANGE_TARGET)) {
                new OpenShiftHelper().prepareForCD(this, context)
            }
        }
    }
    for(String envKeyName: context.env.keySet() as String[]){
        String stageDeployName=envKeyName.toUpperCase()
        if (!"DEV".equalsIgnoreCase(stageDeployName) && "master".equalsIgnoreCase(env.CHANGE_TARGET)){
            stage("Readiness - ${stageDeployName}") {
                node('master') {
                    new OpenShiftHelper().waitUntilEnvironmentIsReady(this, context, envKeyName)
                }
            }
            stage("Approve - ${stageDeployName}") {
                input id: "deploy_${stageDeployName.toLowerCase()}", message: "Deploy to ${stageDeployName}?", ok: 'Approve', submitterParameter: 'approved_by'
            }
        }

        if ("DEV".equalsIgnoreCase(stageDeployName) || "master".equalsIgnoreCase(env.CHANGE_TARGET)){
            stage("Deploy - ${stageDeployName}") {
                node('master') {
                    new OpenShiftHelper().deploy(this, context, envKeyName)
                }
            }
        }
    }

}
