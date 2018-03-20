
/*
* References:
* - https://zwischenzugs.com/2017/04/23/things-i-wish-i-knew-before-using-jenkins-pipelines/
* - https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
* - https://jenkins.io/doc/pipeline/examples/
*/

import hudson.model.Result;
import jenkins.model.CauseOfInterruption.UserInterruption;
import org.kohsuke.github.*;


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
                        openshift.withCluster() {
                            //create or patch DCs
                            echo 'Processing Templates'
                            def models = openshift.process("-f", "openshift.bc.json",
                                    "-p", "APP_NAME=${metadata.appName}",
                                    "-p", "ENV_NAME=${metadata.buildEnvName}",
                                    "-p", "NAME_PREFIX=${metadata.buildNamePrefix}",
                                    "-p", "NAME_SUFFIX=${metadata.buildNameSuffix}",
                                    "-p", "GIT_REPO_URL=${metadata.gitRepoUrl}")
                            
                            echo 'Processing template ...'
                            openShiftApplyBuildConfig(openshift, metadata.appName, metadata.buildEnvName, models)
                            
                            echo 'Creating/Updating Objects (from template)'
                            def builds=[];
                            builds.add(openShiftStartBuild(openshift, ['app-name':appName, 'env-name':buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
                            openShiftWaitForBuilds(openshift, builds)
                            
                        }

                    } //end script
                } //end steps
            } // end stage
            stage('deploy - DEV') {
                agent any
                when { expression { return true} }
                steps {
                    echo 'Deploying'
                    script {
                        def dcPrefix=metadata.appName;
                        def dcSuffix='-dev';
                        def envName="dev"

                        if (metadata.isPullRequest){
                            envName = "pr-${pullRequestNumber}"
                            dcSuffix="-pr-${pullRequestNumber}";
                        }

                        openshift.withCluster() {
                            def buildProjectName="${openshift.project()}"
                            def buildImageStreams=[:];
                            openshift.selector( 'is', ['app-name':metadata.appName, 'env-name':metadata.buildEnvName]).withEach {
                                buildImageStreams["${it.object().metadata.name}"]=true;
                            }

                            echo "buildImageStreams:${buildImageStreams}"
                            openshift.withCredentials( 'jenkins-deployer-dev.token' ) {
                                openshift.withProject( 'csnr-devops-lab-deploy' ) {
                                    def models = [];
                                    models.addAll(openshift.process(
                                            'openshift//mysql-ephemeral',
                                            "-p", "DATABASE_SERVICE_NAME=${dcPrefix}-db${dcSuffix}",
                                            '-p', "MYSQL_DATABASE=petclinic"
                                    ));

                                    models.addAll(openshift.process("-f", "openshift.dc.json",
                                            "-p", "APP_NAME=${appName}",
                                            "-p", "ENV_NAME=${envName}",
                                            "-p", "NAME_PREFIX=${dcPrefix}",
                                            "-p", "NAME_SUFFIX=${dcSuffix}",
                                            "-p", "BC_PROJECT=${openshift.project()}",
                                            "-p", "DC_PROJECT=${openshift.project()}"
                                    ));
                                    
                                    openShiftApplyDeploymentConfig(openshift, buildProjectName, appName, envName, models, buildImageStreams)
                                    
                                } // end openshift.withProject()
                            } // end openshift.withCredentials()
                        } // end openshift.withCluster()

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
