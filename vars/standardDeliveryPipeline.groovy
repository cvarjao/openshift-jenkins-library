
/*
* References:
* - https://zwischenzugs.com/2017/04/23/things-i-wish-i-knew-before-using-jenkins-pipelines/
* - https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
* - https://jenkins.io/doc/pipeline/examples/
*/

import hudson.model.Result;
import jenkins.model.CauseOfInterruption.UserInterruption;
import org.kohsuke.github.*;
import bcgov.OpenShiftHelper;

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
        }
        agent none
        stages {
            stage('Prepare') {
                agent { label 'maven' }
                when { expression { return true } }
                steps {
                    milestone(1)
                    script { abortAllPreviousBuildInProgress }
                    checkout scm
                    script {
                        loadBuildMetadata(metadata);
                        echo "metadata:\n${metadata}"
                        def stashIncludes=[]
                        for ( def templateCfg : context.bcModels + context.dcModels){
                            if ('-f'.equalsIgnoreCase(templateCfg[0])){
                                stashIncludes.add(templateCfg[1])
                            }
                        }
                        stash(name: 'openshift', includes:stashIncludes.join(','))
                    } //end script
                }
            }
            stage('GitHub Deployment (Start)') {
                agent any
                when { expression { return false} }
                steps {
                    script {
                        ghDeployment(metadata.commit, "PREVIEW")
                    }
                }
            }
            stage('Build') {
                agent any
                when { expression { return true} }
                steps {
                    script {
                        echo 'Building ...'
                        unstash(name: 'openshift')
                        new OpenShiftHelper().build(this,[
                            'metadata': metadata,
                            'models': context.bcModels
                        ])

                    } //end script
                } //end steps
            } // end stage
            stage('deploy - DEV') {
                agent any
                when { expression { return true} }
                steps {
                    script {
                        echo 'Deploying'
                        unstash(name: 'openshift')
                        new OpenShiftHelper().deploy(this,[
                                'projectName': 'csnr-devops-lab-deploy',
                                'envName': "dev-pr-${metadata.pullRequestNumber}",
                                'metadata': metadata,
                                'models': context.dcModels
                        ])
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
