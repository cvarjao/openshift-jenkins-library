
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
    def context= ['openshift':['templates':['includes':'openshift/*.json']]]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = context
    body()

    def metadata=['appName':context.name]
    

    pipeline {
        // The options directive is for configuration that applies to the whole job.
        options {
            // Keep 10 builds at a time
            buildDiscarder(logRotator(numToKeepStr:'10'))
            skipDefaultCheckout()
            //durabilityHint('PERFORMANCE_OPTIMIZED')
        }
        agent none
        stages {
            stage('Prepare') {
                agent none
                when { expression { return true } }
                steps {
                    script {
                        abortAllPreviousBuildInProgress(currentBuild)
                        //GitHubHelper.getPullRequest(this).comment("Starting pipeline [build #${currentBuild.number}]()")
                    }
                }
            }
            stage('Build') {
                agent { label 'master' }
                when { expression { return true } }
                steps {
                    script {
                        abortAllPreviousBuildInProgress
                        echo "BRANCH_NAME=${env.BRANCH_NAME}\nCHANGE_ID=${env.CHANGE_ID}\nCHANGE_TARGET=${env.CHANGE_TARGET}\nBUILD_URL=${env.BUILD_URL}"
                        //echo sh(script: 'env|sort', returnStdout: true)
                    }
                    checkout scm
                    script {

                        def ghDeploymentId = new GitHubHelper().createDeployment(this, GitHubHelper.getPullRequest(this).getHead().getSha(), ['environment':'build'])
                        //GitHubHelper.getPullRequest(this).comment("Build in progress")
                        new GitHubHelper().createDeploymentStatus(this, ghDeploymentId, 'SUCCESS', [:])

                        loadBuildMetadata(metadata)
                        echo "metadata:\n${metadata}"
                        def stashIncludes=[]
                        for ( def templateCfg : context.bcModels + context.dcModels){
                            if ('-f'.equalsIgnoreCase(templateCfg[0])){
                                stashIncludes.add(templateCfg[1])
                            }
                        }
                        stash(name: 'openshift', includes:stashIncludes.join(','))
                        echo 'Building ...'
                        unstash(name: 'openshift')
                        new OpenShiftHelper().build(this,[
                                'metadata': metadata,
                                'models': context.bcModels
                        ])

                        //GitHubHelper.getPullRequest(this).comment("Build complete")
                    } //end script
                }
            }
            stage('Deploy - DEV') {
                agent any
                when { expression { return true} }
                steps {
                    script {
                        echo 'Deploying'
                        String envName="dev-pr-${metadata.pullRequestNumber}"
                        //long ghDeploymentId = GitHubHelper.createDeployment(this, metadata.commit, ['environment':envName])

                        //GitHubHelper.getPullRequest(this).comment("Deploying to DEV")
                        unstash(name: 'openshift')
                        new OpenShiftHelper().deploy(this,[
                                'projectName': 'csnr-devops-lab-deploy',
                                'envName': envName,
                                'metadata': metadata,
                                'models': context.dcModels
                        ])
                        //GitHubHelper.createDeploymentStatus(this, ghDeploymentId, GHDeploymentState.SUCCESS).create()
                        //GitHubHelper.getPullRequest(this).comment("Deployed to DEV")
                    } //end script
                }
            } // end stage
            stage('Deploy - TEST') {
                agent any
                when { expression { return false} }
                steps {
                    echo "Testing ..."
                    echo "Testing ... Done!"
                }
            }
            stage('Deploy - PROD') {
                agent any
                when { expression { return false} }
                steps {
                    echo "Packaging ..."
                    echo "Packaging ... Done!"
                }
            }
            stage('Cleanup') {
                agent any
                when { expression { return false} }
                steps {
                    echo "Publishing ..."
                    echo "Publishing ... Done!"
                }
            }
        }
    }

}
