package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;

class OpenShiftHelper {

    def build(CpsScript script, Map __context) {
        def openshift=script.openshift;

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

    def applyBuildConfig(CpsScript script, openshift, appName, envName, models) {
        //def body = {
        script.echo "OpenShiftHelper.applyBuildConfig: Hello - ${script.dump()}"
        script.echo "openShiftBuild:openshift2:${openshift.dump()}"
        //} //end 'body 'closure

        def bcSelector = ['app-name': appName, 'env-name': envName];

        script.echo "openShiftApplyBuildConfig:openshift1:${openshift.dump()}"

        script.echo "Cancelling all pending builds"
        openshift.selector('bc', bcSelector).cancelBuild();

        script.echo "Waiting for all pending builds to complete or cancel"
        openshift.selector('builds', bcSelector).watch {
            if (it.count() == 0) return true
            def allDone = true
            it.withEach {
                def buildModel = it.object()
                if (it.object().status.phase != "Complete" && it.object().status.phase != "Failed") {
                    allDone = false
                }
            }
            return allDone;
        }

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


    def startBuild(CpsScript script, openshift, baseSelector, commitId) {
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


    def waitForBuilds(CpsScript script, openshift, List builds) {
        //Wait for all builds to complete
        openshift.selector(builds).watch {
            def build = it.object();
            def buildDone = ("Complete".equalsIgnoreCase(build.status.phase) || "Cancelled".equalsIgnoreCase(build.status.phase))
            if (!buildDone) {
                echo "Waiting for '${it.name()}' (${build.status.phase})"
            }
            return buildDone;
        }

        openshift.selector(builds).withEach { build ->
            def bo = build.object(); // build object
            if (!"Complete".equalsIgnoreCase(bo.status.phase)) {
                error "Build '${build.name()}' did not successfully complete (${bo.status.phase})"
            }
        }
    }

}