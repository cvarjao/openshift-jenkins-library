package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;
import com.openshift.jenkins.plugins.OpenShiftDSL;

class OpenShiftHelper {
    @NonCPS
    private def getImageChangeTriggerBuildConfig(m, models) {
        if (m.spec.triggers){
            for (def trigger:m.spec.triggers){
                if ('ImageChange'.equalsIgnoreCase(trigger.type)){
                    if (m.spec.strategy!=null &&
                            m.spec.strategy.sourceStrategy!=null &&
                            m.spec.strategy.sourceStrategy.from!=null &&
                            m.spec.strategy.sourceStrategy.from.namespace == null &&
                            'ImageStreamTag'.equalsIgnoreCase(m.spec.strategy.sourceStrategy.from.kind)){
                        for (def m1 in models) {
                            if ('BuildConfig'.equalsIgnoreCase(m1.kind) &&
                                    m1.spec.output.to!=null &&
                                    m1.spec.output.to.namespace==null &&
                                    'ImageStreamTag'.equalsIgnoreCase(m1.spec.output.to.kind) &&
                                    m1.spec.output.to.name.equalsIgnoreCase(m.spec.strategy.sourceStrategy.from.name)
                            ){
                                return m1
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    def build(CpsScript script, Map __context) {
        OpenShiftDSL openshift=script.openshift;

        //File bcScriptFactoryFile=new File(script.pwd(), 'openshift.bc.groovy');
        //script.echo "bcScriptFactoryFile:${bcScriptFactoryFile.getText()}"

        script.echo "openShiftBuild:openshift1:${openshift.dump()}"
        openshift.withCluster() {
            script.echo "openShiftBuild:openshift2:${openshift.dump()}"
            openshift.withProject(openshift.project()) {
                def models = [];
                def metadata = __context.metadata;

                script.echo "openShiftBuild:openshift3:${openshift.dump()}"
                script.echo "openShiftBuild: project:${openshift.project()}"

                script.echo "metadata:\n${metadata}"

                if (__context.models != null) {
                    def modelsDef = __context.models
                    def bindings = __context
                    for (def template:modelsDef){
                        def params=processStringTemplate(template, bindings);
                        models.addAll(openshift.process(params.remove(0), params))
                    }
                }

                //script.echo 'Processing template ...'
                for (m in models) {
                    if ('BuildConfig'.equalsIgnoreCase(m.kind)){
                        String commitId = metadata.commit
                        String contextDir=null

                        if (m.spec && m.spec.source && m.spec.source.contextDir){
                            contextDir=m.spec.source.contextDir
                        }

                        if (contextDir!=null && contextDir.startsWith('/') && !contextDir.equalsIgnoreCase('/')){
                            contextDir=contextDir.substring(1)
                        }

                        if (contextDir!=null){
                            commitId=script.sh(returnStdout: true, script: "git rev-list -1 HEAD -- '${contextDir}'").trim()
                        }
                        if (!m.metadata.annotations) m.metadata.annotations=[:]
                        if (m.spec.source.git.ref) m.metadata.annotations['source/spec.source.git.ref']=m.spec.source.git.ref

                        m.metadata.annotations['spec.source.git.ref']=commitId
                        //Disable ConfigChange trigger
                        /*
                        if (m.spec.triggers){
                            def newTriggers=[]
                            m.metadata.annotations['source/spec.triggers'] = toJsonString(m.spec.triggers)
                            for(def trigger:m.spec.triggers){
                                if (!'ConfigChange'.equalsIgnoreCase(trigger.type)){
                                    newTriggers.add(trigger)
                                }
                            }
                            m.spec.triggers=newTriggers
                        }
                        */
                        m.spec.source.git.ref=commitId
                        //script.echo "Setting commit '${m.spec.source.git.ref}' for 'bc/${m.metadata.name}'"
                    }
                }

                applyBuildConfig(script, openshift, metadata.appName, metadata.buildEnvName, models);
                //Wait 10 seconds for triggers to kick in
                script.sleep 10

                //script.echo 'Creating/Updating Objects (from template)'
                def builds = []

                def _deferredBuilds=[]

                for (m in models) {
                    if ('BuildConfig'.equalsIgnoreCase(m.kind)){
                        script.echo "Processing 'bc/${m.metadata.name}'"
                        String commitId = m.metadata.annotations['spec.source.git.ref']
                        if (m.status==null) m.status=[:]
                        def startNewBuild=true


                        /*
                        def hasConfigChangeTrigger=false
                        if (m.spec.triggers){
                            for (def trigger:m.spec.triggers){
                                if ('ConfigChange'.equalsIgnoreCase(trigger.type)){
                                    hasConfigChangeTrigger=true
                                }
                            }
                        }
                        if (hasConfigChangeTrigger) {
                            openshift.set(['triggers', "bc/${m.metadata.name}", '--from-config', '--remove'])
                        }
                        openshift.selector("bc/${m.metadata.name}").patch('\'{"spec":{"source":{"git":{"ref": "'+commitId+'"}}}}\'')
                        if (hasConfigChangeTrigger) {
                            openshift.set(['triggers', "bc/${m.metadata.name}", '--from-config'])
                        }
                        */
                        def sourceBuildConfig=getImageChangeTriggerBuildConfig(m, models)
                        if (sourceBuildConfig!=null && sourceBuildConfig.status.newBuild){
                            _deferredBuilds.add(openshift.selector("bc/${m.metadata.name}").object())
                            startNewBuild=false
                        }

                        if (startNewBuild==true) {
                            def buildSelector = null

                            openshift.selector('builds', ['openshift.io/build-config.name': "${m.metadata.name}", 'commit-id': "${commitId}"]).withEach{ build ->
                                if (isBuildSuccesful(build.object())){
                                    buildSelector=openshift.selector(build.name())
                                }
                            }

                            if (buildSelector == null || buildSelector.count() == 0) {
                                script.echo "Starting new build for 'bc/${m.metadata.name}' with commit ${commitId}"
                                buildSelector = openshift.selector("bc/${m.metadata.name}").startBuild("--commit=${commitId}")
                                script.echo "New build started - ${buildSelector.name()}"
                                buildSelector.label(['commit-id': "${commitId}"], "--overwrite")
                                m.status.newBuild=buildSelector.name()
                                builds.add(buildSelector.name())
                            } else {
                                builds.add(buildSelector.name())
                                script.echo "Skipping new build. Reusing '${buildSelector.name()}'"
                            }
                        }else{
                            script.echo "Build for 'bc/${m.metadata.name}' has been deferred"
                        }
                    }
                }

                while(builds.size()>0) {
                    script.echo "Waiting for builds to complete"
                    //builds.add(startBuild(script, openshift, ['app-name': metadata.appName, 'env-name': metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
                    waitForBuilds(script, openshift, builds)
                    openshift.selector(builds).withEach { build ->
                        build.label(['commit-id': "${build.object().spec.source.git.ref}"], "--overwrite")
                    }
                    openshift.selector(builds).withEach { build ->
                        def bo = build.object() // build object
                        if (!isBuildSuccesful(bo)) {
                            script.error "Build '${build.name()}' did not successfully complete (${bo.status.phase})"
                        }
                    }
                    builds.clear()
                    script.sleep 10 //wait for triggers to kick in
                    for (m in models) {
                        if ('BuildConfig'.equalsIgnoreCase(m.kind)){
                            def o=openshift.selector("bc/${m.metadata.name}").object(exportable:true)
                            def buildName="build/${m.metadata.name}-${o.status.lastVersion}"
                            def build=openshift.selector("build/${m.metadata.name}-${o.status.lastVersion}")
                            if (build.count()>0){
                                def bo=build.object(exportable:true)
                                if (!isBuildComplete(bo)){
                                    if (!builds.contains(buildName)){
                                        builds.add(buildName)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private def applyBuildConfig(CpsScript script, OpenShiftDSL openshift, appName, envName, models) {
        //def body = {
        script.echo "OpenShiftHelper.applyBuildConfig: Hello - ${script.dump()}"
        script.echo "openShiftBuild:openshift2:${openshift.dump()}"
        //} //end 'body 'closure

        def bcSelector = ['app-name': appName, 'env-name': envName];

        script.echo "openShiftApplyBuildConfig:openshift1:${openshift.dump()}"

        script.echo "Cancelling all pending builds"
        if (openshift.selector('bc', bcSelector).count() >0 ){
            openshift.selector('bc', bcSelector).cancelBuild();
        }

        script.echo "Waiting for all pending builds to complete or cancel"
        
        waitForBuildsWithSelector(script, openshift, openshift.selector('builds', bcSelector));


        script.echo "Processing ${models.size()} objects for '${appName}' for '${envName}'"
        def creations=[]
        def updates=[]
        for (o in models) {
            script.echo "Processing '${o.kind}/${o.metadata.name}' (before apply)"
            if (o.metadata.labels==null) o.metadata.labels =[:]
            o.metadata.labels["app"] = "${appName}-${envName}"
            o.metadata.labels["app-name"] = "${appName}"
            o.metadata.labels["env-name"] = "${envName}"

            def sel=openshift.selector("${o.kind}/${o.metadata.name}");
            if (sel.count()==0){
                script.echo "Creating '${o.kind}/${o.metadata.name}'"
                creations.add(o);
            }else{
                if (!'ImageStream'.equalsIgnoreCase("${o.kind}")){
                    script.echo "Updating '${o.kind}/${o.metadata.name}'"
                    updates.add(o);
                }else{
                    script.echo "Skipping '${o.kind}/${o.metadata.name}' (Already Exists)"
                }
            }

        }

        if (creations.size()>0){
            script.echo "Creating ${creations.size()} objects"
            openshift.apply(creations);
        }
        if (updates.size()>0){
            script.echo "Updating ${updates.size()} objects"
            openshift.apply(updates);
        }

        //body.resolveStrategy = Closure.DELEGATE_FIRST;
        //body.delegate = script;
        //body();

        //def bcSelector=['app-name':appName, 'env-name':envName];
        //echo "Cancelling all pending builds"
        //openshift.selector( 'bc', bcSelector).cancelBuild();
    }


    private def startBuild(CpsScript script, OpenShiftDSL openshift, baseSelector, commitId) {
        String buildNameSelector = null;

        def buildSelector = openshift.selector('builds', baseSelector + ['commit-id': "${commitId}"]);

        if (buildSelector.count() == 0) {
            script.echo "Starting new build for '${baseSelector}'"
            buildSelector = openshift.selector('bc', baseSelector).startBuild("--commit=${commitId}")
            script.echo "New build started - ${buildSelector.name()}"
            buildSelector.label(['commit-id': "${commitId}"], "--overwrite")
            buildNameSelector = buildSelector.name()
        } else {
            buildNameSelector = buildSelector.name()
            script.echo "Skipping new build. Reusing '${buildNameSelector}'"
            //def build=buildSelector.object()
            //echo "OutputImageDigest: '${build.status.output.to.imageDigest}'"
            //echo "outputDockerImageReference: '${build.status.outputDockerImageReference}'"
        }
        return buildNameSelector;
    }


    private def waitForBuilds(CpsScript script, OpenShiftDSL openshift, List builds) {
        //Wait for all builds to complete
        waitForBuildsWithSelector(script, openshift, openshift.selector(builds));
    }

    private def freeze(OpenShiftDSL openshift, selector) {
        return openshift.selector(selector.names());
    }

    private def isBuildComplete(build) {
        return ("Complete".equalsIgnoreCase(build.status.phase) || "Cancelled".equalsIgnoreCase(build.status.phase) || "Failed".equalsIgnoreCase(build.status.phase))
    }

    private def isBuildSuccesful(build) {
        return "Complete".equalsIgnoreCase(build.status.phase)
    }

    private def waitForBuildsWithSelector(CpsScript script, OpenShiftDSL openshift, selector) {
        def names=selector.names()
        if (names.size() > 0){
            for (String name:names){
                script.echo "Checking status of '${name}'"
                openshift.selector(name).watch {
                    return isBuildComplete(it.object())
                }
            }
            /*
            openshift.selector(selector.names()).withEach { build ->
                script.echo "Checking status of '${build.name()}'"
                if (!isBuildComplete(build.object())){
                    build.watch {
                        return isBuildComplete(it.object())
                    }
                }
            }
            */

            /*
            def queue = []
            queue.addAll(selector.names())

            while (queue.count()>0){
                def item=queue[0]
                script.echo "Checking status of '${item}'"
                if (!isBuildComplete(openshift.selector(item).object())){
                    openshift.selector(item).watch {
                        return isBuildComplete(it.object())
                    }
                }
                queue.remove(0)
            }
            */
        }
    } // end method


    def deploy(CpsScript script, Map context) {
        OpenShiftDSL openshift=script.openshift
        Map metadata = context.metadata

        if (!context.dcPrefix) context.dcPrefix=metadata.appName
        if (!context.dcSuffix) context.dcSuffix="-${context.envName}"

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

                    context.buildProject=buildProjectName
                    context.deployProject=context.projectName

                    if (context.models != null) {
                        def modelsDef = context.models
                        def bindings = context
                        for (def template:modelsDef){
                            def params=processStringTemplate(template, bindings);

                            models.addAll(openshift.process(params.remove(0), params))
                        }
                    }


                    script.echo "DeployModels:${models}"
                    applyDeploymentConfig(script, openshift, buildProjectName, metadata.appName, context.envName, models, buildImageStreams)


                } // end openshift.withProject()
            } // end openshift.withCredentials()
        } // end openshift.withCluster()
    } // end 'deploy' method
    @NonCPS
    private def toJsonString(object) {
        return new groovy.json.JsonBuilder(object).toPrettyString()
    }

    @NonCPS
    private def processStringTemplate(String template, Map bindings) {
        def engine = new groovy.text.GStringTemplateEngine()
        return engine.createTemplate(template).make(bindings).toString()
    }

    @NonCPS
    private def processStringTemplate(List params, Map bindings) {
        def engine = new groovy.text.GStringTemplateEngine()
        def ret=[]
        for (def param:params) {
            ret.add(engine.createTemplate(param).make(bindings).toString())
        }
        return ret
    }

    private def updateContainerImages(CpsScript script, OpenShiftDSL openshift, containers, triggers) {
        for ( c in containers ) {
            for ( t in triggers) {
                if ('ImageChange'.equalsIgnoreCase(t['type'])){
                    for ( cn in t.imageChangeParams.containerNames){
                        if (cn.equalsIgnoreCase(c.name)){
                            script.echo "${t.imageChangeParams.from}"
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

                            script.echo "ImageReference is '${dockerImageReference}'"
                            c.image = "${dockerImageReference}";
                        }
                    }
                }
            }
        }
    }

    private def applyDeploymentConfig(CpsScript script, OpenShiftDSL openshift, String buildProjectName, String appName, String envName, List models, buildImageStreams) {
        def dcSelector=['app-name':appName, 'env-name':envName];
        def replicas=[:]
        for ( m in models ) {
            if ("DeploymentConfig".equals(m.kind)){
                replicas[m.metadata.name]=m.spec.replicas
                m.spec.replicas = 0
                m.spec.paused = true
                updateContainerImages(script, openshift, m.spec.template.spec.containers, m.spec.triggers);
            }
        }

        //echo "Scaling down"
        // openshift.selector( 'dc', dcSelector).scale('--replicas=0', '--timeout=2m')
        openshift.selector( 'dc', dcSelector).freeze().withEach { dc ->
            def o = dc.object();
            replicas[o.metadata.name]=o.spec.replicas
            script.echo "'${dc.name()}'  paused=${o.spec.paused}"
            if ( o.spec.paused == false ){
                dc.rollout().pause()
            }
        }

        script.echo "The template will create/update ${models.size()} objects"
        //TODO: needs to review usage of 'apply', it recreates Secrets!!!
        def secrets=models.findAll();
        def configSets=models.findAll();
        def others=models.findAll();

        def selector=openshift.apply(others);

        selector.label(['app':"${appName}-${envName}", 'app-name':"${appName}", 'env-name':"${envName}"], "--overwrite")

        selector.narrow('is').withEach { imageStream ->
            def o=imageStream.object();
            def imageStreamName="${o.metadata.name}"

            if (buildImageStreams[imageStreamName] != null ){
                script.echo "Tagging '${buildProjectName}/${o.metadata.name}:latest' as '${o.metadata.name}:${envName}'"
                openshift.tag("${buildProjectName}/${o.metadata.name}:latest", "${o.metadata.name}:${envName}")
            }
        }
        script.echo 'Resuming DeploymentConfigs'
        openshift.selector( 'dc', dcSelector).freeze().withEach { dc ->
            def o = dc.object();
            script.echo "'${dc.name()}'  paused=${o.spec.paused}"
            if (o.spec.paused == true){
                dc.rollout().resume()
            }
        }
        script.echo 'Cancelling DeploymentConfigs'
        script.echo "${openshift.selector( 'dc', dcSelector).freeze().rollout().cancel()}"
        script.echo "Waiting for RCs to get cancelled"
        //openshift.verbose(true);
        openshift.selector( 'rc', dcSelector).watch { rcs ->
            def allDone=true;
            script.echo "Waiting for '${rcs.names()}'"

            rcs.freeze().withEach { rc ->
                def o = rc.object();
                def phase=o.metadata.annotations['openshift.io/deployment.phase']
                if (!( 'Failed'.equalsIgnoreCase(phase) || 'Complete'.equalsIgnoreCase(phase))){
                    allDone=false;
                }
            }
            return allDone;
        }

        script.echo "Deployments:"
        script.echo "${openshift.selector( 'dc', dcSelector).freeze().rollout().latest()}"
        openshift.selector( 'rc', dcSelector).watch { rcs ->
            def allDone=true;
            rcs.freeze().withEach { rc ->
                def o = rc.object();
                def phase=o.metadata.annotations['openshift.io/deployment.phase']
                if (!( 'Failed'.equalsIgnoreCase(phase) || 'Complete'.equalsIgnoreCase(phase))){
                    allDone=false;
                }
            }
            return allDone;
        }

        script.echo 'Scaling-up application'
        openshift.selector( 'dc', dcSelector).freeze().withEach { dc ->
            def o=dc.object();
            openshift.selector(dc.name()).scale("--replicas=${replicas[o.metadata.name]}", '--timeout=2m')
        }
        script.echo 'Waiting for pods to become ready'
        openshift.selector( 'dc', dcSelector).watch{ dc ->
            def objects=dc.objects()

            for (def o: objects){
                if (!(o.status && o.status.readyReplicas == replicas[o.metadata.name])){
                    return false
                }
            }
            return true
        }
        //openshift.selector("dc/nginx").rollout().resume()

        //openshift.selector( 'dc', dcSelector).scale('--replicas=0', '--timeout=2m')
        //script.echo "deploy:\n${openshift.selector( 'dc', dcSelector).rollout().cancel()}"
        //script.echo "deploy:\n${openshift.selector( 'dc', dcSelector).rollout().latest()}"
        //script.echo "deploy:\n${openshift.selector( 'dc', dcSelector).rollout().status()}"
        //openshift.selector( 'dc', dcSelector).scale('--replicas=1', '--timeout=4m')
    }

} // end class
