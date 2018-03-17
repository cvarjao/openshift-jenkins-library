
/*
* References:
* - https://zwischenzugs.com/2017/04/23/things-i-wish-i-knew-before-using-jenkins-pipelines/
* - https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
*
*/

import hudson.model.Result;
import jenkins.model.CauseOfInterruption.UserInterruption;
import org.kohsuke.github.*;


def killOldBuilds() {
    while(currentBuild.rawBuild.getPreviousBuildInProgress() != null) {
        currentBuild.rawBuild.getPreviousBuildInProgress().doKill()
    }
}

def updateContainerImages(containers, triggers) {
    for ( c in containers ) {
        for ( t in triggers) {
            if ('ImageChange'.equalsIgnoreCase(t['type'])){
                for ( cn in t.imageChangeParams.containerNames){
                    if (cn.equalsIgnoreCase(c.name)){
                        echo "${t.imageChangeParams.from}"
                        def dockerImageReference = '';
                        def selector=openshift.selector("istag/${t.imageChangeParams.from.name}");

                        if (t.imageChangeParams.from['namespace']!=null && t.imageChangeParams.from['namespace'].length()>0){
                            openshift.withProject(t.imageChangeParams.from['namespace']) {
                                selector=openshift.selector("istag/${t.imageChangeParams.from.name}");
                                if (selector.count() == 1 ){
                                    dockerImageReference=selector.object().image.dockerImageReference
                                }
                            }
                        }else{
                            selector=openshift.selector("istag/${t.imageChangeParams.from.name}");
                            if (selector.count() == 1 ){
                                dockerImageReference=selector.object().image.dockerImageReference
                            }
                        }

                        echo "ImageReference is '${dockerImageReference}'"
                        c.image = "${dockerImageReference}";
                    }
                }
            }
        }
    }
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
                agent any
                steps {
                    milestone(1)
                    script {
                        sayHello('World')
                        killOldBuilds();
                    }
                    checkout scm
                    //sh "git rev-parse HEAD"
                    //sh "git ls-remote"
                    sh "git show-ref --head"
                    sh "git show-ref --head --dereference"
                    sh "env"
                    //sh "git branch"
                    //sh "git branch -a"
                    //sh "git status"
                    //sh "git status -sb"
                    echo 'Building Branch: ' + env.BRANCH_NAME
                    echo 'Build Number: ' + env.BUILD_NUMBER
                    echo 'CHANGE_ID: ' + env.CHANGE_ID
                    echo "CHANGE_TARGET: ${env.CHANGE_TARGET}"
                    echo "JOB_NAME: ${env.JOB_NAME}"
                    echo "JOB_BASE_NAME: ${env.JOB_BASE_NAME}"
                    //timeout(time: 10, unit: 'MINUTES') {
                    //  echo "Checkout ..."
                    //  echo "Checkout ... Done!"
                    //}
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
                } // end steps
            }
            stage('GitHub Deployment (Start)') {
                agent any
                when { expression { return true} }
                steps {
                    script {
                        ghDeployment(gitCommitId, "PREVIEW")
                    }
                }
            }
            stage('Build') {
                agent any
                when { expression { return false} }
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
                            def models = openshift.process("-f", "openshift.bc.json",
                                    "-p", "APP_NAME=${appName}",
                                    "-p", "ENV_NAME=${buildEnvName}",
                                    "-p", "NAME_PREFIX=${bcPrefix}",
                                    "-p", "NAME_SUFFIX=${bcSuffix}",
                                    "-p", "GIT_REPO_URL=${gitRepoUrl}")
                            echo "The template will create/update ${models.size()} objects"
                            for ( o in models ) {
                                o.metadata.labels[ "app" ] = "${appName}-${buildEnvName}"
                                /*
                                def sel=openshift.selector("${o.kind}/${o.metadata.name}");
                                if (sel.count()==0){
                                    echo "Creating '${o.kind}/${o.metadata.name}"
                                    openshift.create([o]);
                                }else{
                                    echo "Patching '${o.kind}/${o.metadata.name}"
                                    openshift.apply(o);
                                }
                                */

                            }
                            openshift.apply(models);

                            def gitAppCommitId = sh(returnStdout: true, script: 'git rev-list -1 HEAD -- spring-petclinic').trim()
                            echo "gitAppCommitId:${gitAppCommitId}"

                            def buildSelector = openshift.selector( 'builds', bcSelector + ['commit-id':"${gitAppCommitId}"]);
                            if (buildSelector.count()==0){
                                echo "Starting new build"
                                buildSelector = openshift.selector( 'bc', bcSelector).narrow('bc').startBuild("--commit=${buildRefBranchName}")
                                echo "New build started - ${buildSelector.name()}"
                                buildSelector.label(['commit-id':"${gitAppCommitId}"], "--overwrite")
                                buildSelector.logs('-f');

                                openshift.selector("${buildSelector.name()}").watch {
                                    def build=it.object();
                                    return !"Running".equalsIgnoreCase(build.status.phase)
                                }

                                def build=openshift.selector("${buildSelector.name()}").object();
                                if (!"Complete".equalsIgnoreCase(build.status.phase)){
                                    error "Build '${buildSelector.name()}' did not successfully complete (${build.status.phase})"
                                }
                                echo "OutputImageDigest: '${build.status.output.to.imageDigest}'"
                                echo "outputDockerImageReference: '${build.status.outputDockerImageReference}'"
                            }else{
                                echo "Skipping new build. Reusing '${buildSelector.name()}'"
                                def build=buildSelector.object()
                                echo "OutputImageDigest: '${build.status.output.to.imageDigest}'"
                                echo "outputDockerImageReference: '${build.status.outputDockerImageReference}'"
                            }


                            //TODO: Re-add build triggers (ImageChange, ConfigurationChange)
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
                                    for ( m in models ) {
                                        if ("DeploymentConfig".equals(m.kind)){
                                            m.spec.replicas = 0
                                            updateContainerImages(m.spec.template.spec.containers, m.spec.triggers);
                                        }
                                    }

                                    echo "Scaling down"
                                    openshift.selector( 'dc', dcSelector).scale('--replicas=0', '--timeout=2m')

                                    echo "The template will create/update ${models.size()} objects"
                                    //TODO: needs to review usage of 'apply' it recreates Secrets!!!
                                    def selector=openshift.apply(models);
                                    selector.label(['app':"${appName}-${envName}", 'app-name':"${appName}", 'env-name':"${envName}"], "--overwrite")

                                    selector.narrow('is').withEach { imageStream ->
                                        def o=imageStream.object();
                                        def imageStreamName="${o.metadata.name}"

                                        if (buildImageStreams[imageStreamName] != null ){
                                            echo "Tagging '${buildProjectName}/${o.metadata.name}:latest' as '${o.metadata.name}:${envName}'"
                                            openshift.tag("${buildProjectName}/${o.metadata.name}:latest", "${o.metadata.name}:${envName}")
                                        }
                                    }



                                    openshift.selector( 'dc', dcSelector).scale('--replicas=1', '--timeout=4m')
                                    //openshift.selector("dc/${dcPrefix}${dcSuffix}").rollout().resume();

                                    //def buildSelector = openshift.selector( 'dc', dcSelector);

                                    //TODO: Re-add build triggers (ImageChange, ConfigurationChange)
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
            stage('publishing') {
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
