package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;
import com.openshift.jenkins.plugins.OpenShiftDSL;

class OpenShiftHelper {

    def build(CpsScript script, Map __context) {
        OpenShiftDSL openshift=script.openshift;

        script.echo "openShiftBuild:openshift1:${openshift.dump()}"
        openshift.withCluster() {
            script.echo "openShiftBuild:openshift2:${openshift.dump()}"
            openshift.withProject(openshift.project()) {
                def models = [];
                def metadata = __context.metadata;

                script.echo "openShiftBuild:openshift3:${openshift.dump()}"
                script.echo "openShiftBuild: project:${openshift.project()}"

                if (__context.models != null) {
                    __context.models.resolveStrategy = Closure.DELEGATE_FIRST;
                    __context.models.delegate = this;
                    models = __context.models();
                }


                script.echo "openShiftBuild: models:${models.dump()}"

                script.echo 'Processing template ...'

                applyBuildConfig(script, openshift, metadata.appName, metadata.buildEnvName, models);

                script.echo 'Creating/Updating Objects (from template)'
                def builds = [];
                builds.add(startBuild(script, openshift, ['app-name': metadata.appName, 'env-name': metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
                waitForBuilds(script, openshift, builds)
            }
        }
    }

    def applyBuildConfig(CpsScript script, OpenShiftDSL openshift, appName, envName, models) {
        //def body = {
        script.echo "OpenShiftHelper.applyBuildConfig: Hello - ${script.dump()}"
        script.echo "openShiftBuild:openshift2:${openshift.dump()}"
        //} //end 'body 'closure

        def bcSelector = ['app-name': appName, 'env-name': envName];

        script.echo "openShiftApplyBuildConfig:openshift1:${openshift.dump()}"

        script.echo "Cancelling all pending builds"
        openshift.selector('bc', bcSelector).cancelBuild();

        script.echo "Waiting for all pending builds to complete or cancel"
        waitForBuildsWithSelector(openshift, openshift.selector('builds', bcSelector));


        script.echo "Applying ${models.size()} objects for '${appName}' for '${envName}'"
        for (o in models) {
            script.echo "Processing '${o.kind}/${o.metadata.name}'"
            o.metadata.labels["app"] = "${appName}-${envName}"
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

        //body.resolveStrategy = Closure.DELEGATE_FIRST;
        //body.delegate = script;
        //body();

        //def bcSelector=['app-name':appName, 'env-name':envName];
        //echo "Cancelling all pending builds"
        //openshift.selector( 'bc', bcSelector).cancelBuild();
    }


    def startBuild(CpsScript script, OpenShiftDSL openshift, baseSelector, commitId) {
        String buildNameSelector = null;

        def buildSelector = openshift.selector('builds', baseSelector + ['commit-id': "${commitId}"]);
        if (buildSelector.count() == 0) {
            script.echo "Starting new build for '${baseSelector}'"
            buildSelector = openshift.selector('bc', baseSelector).startBuild("--commit=${commitId}")
            script.echo "New build started - ${buildSelector.name()}"
            buildSelector.label(['commit-id': "${commitId}"], "--overwrite")
            buildNameSelector = buildSelector.name()
            /*
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
        */
        } else {
            buildNameSelector = buildSelector.name()
            script.echo "Skipping new build. Reusing '${buildNameSelector}'"
            //def build=buildSelector.object()
            //echo "OutputImageDigest: '${build.status.output.to.imageDigest}'"
            //echo "outputDockerImageReference: '${build.status.outputDockerImageReference}'"
        }
        return buildNameSelector;
    }


    def waitForBuilds(CpsScript script, OpenShiftDSL openshift, List builds) {
        //Wait for all builds to complete
        waitForBuildsWithSelector(openshift, openshift.selector(builds));
    }
    def waitForBuildsWithSelector(OpenShiftDSL openshift, selector) {
        openshift.selector(selector.names()).watch {
            def build = it.object();
            def buildDone = ("Complete".equalsIgnoreCase(build.status.phase) || "Cancelled".equalsIgnoreCase(build.status.phase))
            if (!buildDone) {
                echo "Waiting for '${it.name()}' (${build.status.phase})"
            }
            return buildDone;
        }

        openshift.selector(selector.names()).withEach { build ->
            def bo = build.object(); // build object
            if (!"Complete".equalsIgnoreCase(bo.status.phase)) {
                error "Build '${build.name()}' did not successfully complete (${bo.status.phase})"
            }
        }
    } // end method

    def deploy(CpsScript script, Map context) {
        OpenShiftDSL openshift=script.openshift
        Map metadata = context.metadata

        context.dcPrefix=metadata.appName
        context.dcSuffix='-dev'

        if (metadata.isPullRequest){
            context.envName = "pr-${metadata.pullRequestNumber}"
            context.dcSuffix="-pr-${metadata.pullRequestNumber}"
        }
        script.echo "OpenShiftHelper.deploy: Deploying"
        openshift.withCluster() {
            def buildProjectName="${openshift.project()}"
            def buildImageStreams=[:]

            script.echo "Collecting ImageStreams";
            openshift.selector( 'is', ['app-name':metadata.appName, 'env-name':metadata.buildEnvName]).freeze().withEach {
                buildImageStreams["${it.object().metadata.name}"]=true
            }

            script.echo "buildImageStreams:${buildImageStreams}"
            openshift.withCredentials( 'jenkins-deployer-dev.token' ) {
                openshift.withProject( context.projectName ) {
                    def models = [];

                    if (context.models != null) {
                        context.models.resolveStrategy = Closure.DELEGATE_FIRST;
                        context.models.delegate = context + ['openshift':openshift];
                        models = context.models();
                    }


                    script.echo "${models}"
                    //openShiftApplyDeploymentConfig(openshift, buildProjectName, metadata.appName, context.envName, models, buildImageStreams)

                } // end openshift.withProject()
            } // end openshift.withCredentials()
        } // end openshift.withCluster()
    } // end 'deploy' method

}