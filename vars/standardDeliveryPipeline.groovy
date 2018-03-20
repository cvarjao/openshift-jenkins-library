
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

    def appName='spring-petclinic'
    def appId=null;
    def doDeploy=false;
    def gitCommitId=''
    def isPullRequest=false;
    def pullRequestNumber=null;
    def gitBranchRemoteRef=''
    def buildBranchName = null;
    def resourceBuildNameSuffix = '-dev';
    def buildEnvName = 'dev'
    def gitRepoUrl= ''
    def metadata=[:];

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
                        metadata.modules=listModules(pwd())
                        echo "${metadata.modules}"
                    }
                    script {
                        gitCommitId = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        isPullRequest=(env.CHANGE_ID != null && env.CHANGE_ID.trim().length()>0)

                        echo "gitCommitId:${gitCommitId}"
                        echo "isPullRequest:${isPullRequest}"


                        gitRepoUrl = scm.getUserRemoteConfigs()[0].getUrl()
                        def envName = null;


                        if (isPullRequest){
                            pullRequestNumber=env.CHANGE_ID
                            gitBranchRemoteRef="refs/pull/${pullRequestNumber}/head";
                            buildBranchName = env.BRANCH_NAME;
                            sh "git remote -v"
                            sh "git ls-remote origin refs/pull/${pullRequestNumber}/*"
                        }else{
                            buildBranchName = env.BRANCH_NAME;
                            resourceBuildNamePrefix = "-dev";
                        }

                        echo "gitRepoUrl:${gitRepoUrl}"
                        echo "appName:${appName}"
                        echo "appId:${appId}"
                        echo "buildBranchName:${buildBranchName}"
                        echo "scm.getBranches():${scm.getBranches()}"
                        echo "scm.getKey():${scm.getKey()}"
                    } //end script
                }
            }
            stage('GitHub Deployment (Start)') {
                agent any
                when { expression { return false} }
                steps {
                    script {
                        ghDeployment(gitCommitId, "PREVIEW")
                    }
                }
            }
            stage('Build') {
                agent any
                when { expression { return true} }
                steps {
                    script {
                        def bcPrefix=appName;
                        def bcSuffix='-dev';
                        def buildRefBranchName=gitBranchRemoteRef;

                        if (isPullRequest){
                            buildEnvName = "pr-${pullRequestNumber}"
                            bcSuffix="-pr-${pullRequestNumber}";
                        }else{
                            buildEnvName = 'dev'
                        }

                        def bcSelector=['app-name':appName, 'env-name':buildEnvName];

                        openshift.withCluster() {
                            echo "Waiting for all builds to complete/cancel"
                            openshift.selector( 'bc', bcSelector).cancelBuild();

                            openshift.selector( 'builds', bcSelector).watch {
                                if ( it.count() == 0 ) return true
                                def allDone = true
                                it.withEach {
                                    def buildModel = it.object()
                                    //echo "${it.name()}:status.phase: ${it.object().status.phase}"
                                    if ( it.object().status.phase != "Complete" &&  it.object().status.phase != "Failed") {
                                        allDone = false
                                    }
                                }
                                return allDone;
                            }

                            //create or patch DCs
                            echo 'Processing Templates'
                            def models = openshift.process("-f", "openshift.bc.json",
                                    "-p", "APP_NAME=${appName}",
                                    "-p", "ENV_NAME=${buildEnvName}",
                                    "-p", "NAME_PREFIX=${bcPrefix}",
                                    "-p", "NAME_SUFFIX=${bcSuffix}",
                                    "-p", "GIT_REPO_URL=${gitRepoUrl}")
                            
                            echo 'Creating/Updating Objects (from template)'
                            openShiftApplyBuildConfig(openshift, appName, buildEnvName, models)
                            
                            def gitAppCommitId = metadata.modules['spring-petclinic'].commit;
                            echo "gitAppCommitId:${gitAppCommitId}"
                            def builds=[];
                            
                            builds.add(openShiftStartBuild(openshift, bcSelector, gitAppCommitId));
                            openShiftWaitForBuilds(openshift, builds)
                            
                        }

                    } //end script
                } //end steps
            } // end stage
            stage('deploy - DEV') {
                agent any
                when { expression { return false} }
                steps {
                    echo 'Deploying'
                    script {
                        def dcPrefix=appName;
                        def dcSuffix='-dev';
                        def envName="dev"



                        if (isPullRequest){
                            envName = "pr-${pullRequestNumber}"
                            dcSuffix="-pr-${pullRequestNumber}";
                        }

                        def dcSelector=['app-name':appName, 'env-name':envName];

                        openshift.withCluster() {
                            def buildProjectName="${openshift.project()}"
                            def buildImageStreams=[:];
                            openshift.selector( 'is', ['app-name':appName, 'env-name':buildEnvName]).withEach {
                                buildImageStreams["${it.object().metadata.name}"]=true;
                            }

                            echo "buildImageStreams:${buildImageStreams}"
                            openshift.withCredentials( 'jenkins-deployer-dev.token' ) {
                                openshift.withProject( 'csnr-devops-lab-deploy' ) {
                                    def whoamiResult = openshift.raw( 'whoami' )
                                    def models = [];
                                    echo "WhoAmI:${whoamiResult.out}"

                                    //Database
                                    /*
                                    models = openshift.process(
                                        'openshift//postgresql-ephemeral',
                                        "-p", "DATABASE_SERVICE_NAME=${dcPrefix}-pgsql${dcSuffix}",
                                        '-p', "POSTGRESQL_DATABASE=petclinic"
                                    )
                                    echo "The 'openshift/postgresql' template will create/update ${models.size()} objects"
                                    */

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
                                    
                                    openShiftApplyDeploymentConfig(openshift, appName, envName, models)
                                    
                                } // end openshift.withProject()
                            } // end openshift.withCredentials()
                        } // end openshift.withCluster()

                    } //end script
                }
            } // end stage
            stage('Deploy - TEST') {
                agent any
                when { expression { doDeploy == true} }
                steps {
                    echo "Testing ..."
                    echo "Testing ... Done!"
                }
            }
            stage('Deploy - PROD') {
                agent any
                when { expression { doDeploy == true} }
                steps {
                    echo "Packaging ..."
                    echo "Packaging ... Done!"
                }
            }
            stage('Cleanup') {
                agent any
                when { expression { doDeploy == true} }
                steps {
                    echo "Publishing ..."
                    echo "Publishing ... Done!"
                }
            }
        }
    }

}
