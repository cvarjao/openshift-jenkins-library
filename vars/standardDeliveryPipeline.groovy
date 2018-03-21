
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
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def metadata=['appName':'spring-petclinic'];
    

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
                agent { label 'master' }
                when { expression { return true } }
                steps {
                    milestone(1)
                    checkout scm
                    script {
                        loadBuildMetadata(metadata);
                        echo "metadata:\n${metadata}"
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
                        new OpenShiftHelper().build(this,[
                            'metadata': metadata,
                            'models': {
                                return openshift.process("-f", "openshift.bc.json",
                                    "-p", "APP_NAME=${metadata.appName}",
                                    "-p", "ENV_NAME=${metadata.buildEnvName}",
                                    "-p", "NAME_PREFIX=${metadata.buildNamePrefix}",
                                    "-p", "NAME_SUFFIX=${metadata.buildNameSuffix}",
                                    "-p", "GIT_REPO_URL=${metadata.gitRepoUrl}")
                            }
                        ]);

                    } //end script
                } //end steps
            } // end stage
            stage('deploy - DEV') {
                agent any
                when { expression { return true} }
                steps {
                    script {
                        echo 'Deploying'
                        /*
                        openShiftDeploy (metadata, {
                            projectName = 'csnr-devops-lab-deploy'
                            envName = "dev"
                            models = {
                                return [] + openshift.process(
                                            'openshift//mysql-ephemeral',
                                            "-p", "DATABASE_SERVICE_NAME=${dcPrefix}-db${dcSuffix}",
                                            '-p', "MYSQL_DATABASE=petclinic"
                                    ) + openshift.process("-f", "openshift.dc.json",
                                            "-p", "APP_NAME=${metadata.appName}",
                                            "-p", "ENV_NAME=${envName}",
                                            "-p", "NAME_PREFIX=${dcPrefix}",
                                            "-p", "NAME_SUFFIX=${dcSuffix}",
                                            "-p", "BC_PROJECT=${openshift.project()}",
                                            "-p", "DC_PROJECT=${openshift.project()}"
                                    )
                            }
                        })
                        */
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
