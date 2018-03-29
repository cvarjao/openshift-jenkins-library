package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;
import com.openshift.jenkins.plugins.OpenShiftDSL;

class OpenShiftHelper {
    int logLevel=0

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

    private Map loadObjectsFromTemplate(OpenShiftDSL openshift, List templates, Map context){
        def models = [:]
        if (templates !=null && templates.size() > 0) {
            for (def template : templates) {
                def params = processStringTemplate(template, context)
                for (Map model in openshift.process(params.remove(0), params)){
                    models[key(model)] = model
                }
            }
        }
        return models
    }

    private Map loadObjectsByLabel(OpenShiftDSL openshift, Map labels){
        def models = [:]
        def selector=openshift.selector('is,bc,secret,configmap,dc,svc,route', labels)

        if (selector.count()>0) {
            for (Map model : selector.objects(exportable: true)) {
                models[key(model)] = model
            }
        }
        return models
    }

    private Map loadObjectsByName(OpenShiftDSL openshift, List names){
        def models = [:]
        def selector = openshift.selector(names)

        if (selector.count()>0) {
            for (Map model : selector.objects(exportable: true)) {
                models[key(model)] = model
            }
        }
        return models
    }

    private Map loadBuildConfigStatus(OpenShiftDSL openshift, Map labels){
        Map buildOutput = [:]
        def selector=openshift.selector('bc', labels)

        if (selector.count()>0) {
            for (Map bc : selector.objects()) {
                String buildName = "Build/${bc.metadata.name}-${bc.status.lastVersion}"
                Map build = openshift.selector(buildName).object()
                buildOutput[buildName] = [
                        'kind': build.kind,
                        'metadata': ['name':build.metadata.name],
                        'output': [
                                'to': [
                                        'kind': build.spec.output.to.kind,
                                        'name': build.spec.output.to.name
                                ]
                        ],
                        'status': ['phase': build.status.phase]
                ]

                if (isBuildSuccesful(build)) {
                    buildOutput["${build.spec.output.to.kind}/${build.spec.output.to.name}"] = [
                            'kind': build.spec.output.to.kind,
                            'metadata': ['name':build.spec.output.to.name],
                            'imageDigest': build.status.output.to.imageDigest,
                            'outputDockerImageReference': build.status.outputDockerImageReference
                    ]
                }

                buildOutput["${key(bc)}"] = [
                        'kind': bc.kind,
                        'metadata': ['name':bc.metadata.name],
                        'status': ['lastVersion':bc.status.lastVersion, 'lastBuildName':buildName]
                ]
            }
        }
        return buildOutput
    }

    @NonCPS
    private String key(Map model){
        return "${model.kind}/${model.metadata.name}"
    }
    private void waitForDeploymentsToComplete(CpsScript script, OpenShiftDSL openshift, Map labels){
        script.echo "Waiting for deployments with labels ${labels}"
        boolean doCheck=true
        while(doCheck) {
            openshift.selector('rc', labels).watch {
                boolean allDone = true
                it.withEach { item ->
                    def object = item.object()
                    script.echo "${key(object)} - ${object.status.phase}"
                    if (!isReplicationControllerComplete(object)) {
                        allDone = false
                    }
                }
                return allDone
            }
            script.sleep 5
            doCheck=false
            for (Map build:openshift.selector('rc', labels).objects()){
                if (!isReplicationControllerComplete(build)) {
                    doCheck=true
                    break
                }
            }
        }
    }

    private void waitForBuildsToComplete(CpsScript script, OpenShiftDSL openshift, Map labels){
        //openshift.verbose(true)
        script.echo "Waiting for builds with labels ${labels}"
        boolean doCheck=true
        while(doCheck) {
            openshift.selector('builds', labels).watch {
                boolean allDone = true
                it.withEach { item ->
                    def object = item.object()
                    script.echo "${key(object)} - ${object.status.phase}"
                    if (!isBuildComplete(object)) {
                        allDone = false
                    }
                }
                return allDone
            }
            script.sleep 5
            doCheck=false
            for (Map build:openshift.selector('builds', labels).objects()){
                if (!isBuildComplete(build)) {
                    doCheck=true
                    break
                }
            }
        }
        //openshift.verbose(false)
    }

    def build(CpsScript script, Map __context) {
        OpenShiftDSL openshift=script.openshift;


        openshift.withCluster() {
            openshift.withProject(openshift.project()) {
                //def metadata = __context.metadata
                Map labels=['app-name': __context.name, 'env-name': __context.buildEnvName]
                def newObjects = loadObjectsFromTemplate(openshift, __context.bcModels, __context)
                def currentObjects = loadObjectsByLabel(openshift, labels)

                for (Map m : newObjects.values()){
                    if ('BuildConfig'.equalsIgnoreCase(m.kind)){
                        String commitId = __context.commitId
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
                        m.spec.source.git.ref=commitId
                        m.spec.runPolicy = 'SerialLatestOnly'
                        script.echo "${key(m)} - ${contextDir?:'/'} @ ${m.spec.source.git.ref}"
                    }
                }

                def initialBuildConfigState=loadBuildConfigStatus(openshift, labels)

                applyBuildConfig(script, openshift, __context.name, __context.buildEnvName, newObjects, currentObjects);
                script.echo "Waiting for builds to complete"

                waitForBuildsToComplete(script, openshift, labels)
                def startedNewBuilds=false

                def postBuildConfigState=loadBuildConfigStatus(openshift, labels)
                for (Map item: initialBuildConfigState.values()){
                    //script.echo "${item}"
                    if ('BuildConfig'.equalsIgnoreCase(item.kind)){
                        Map newItem=postBuildConfigState[key(item)]
                        Map build=initialBuildConfigState["Build/${item.metadata.name}-${item.status.lastVersion}"]
                        if (item.status.lastVersion == newItem.status.lastVersion && !isBuildSuccesful(build)){
                            openshift.selector(key(item)).startBuild()
                            startedNewBuilds=true
                        }
                    }
                }

                if (startedNewBuilds) {
                    waitForBuildsToComplete(script, openshift, labels)
                }

                def buildOutput=loadBuildConfigStatus(openshift, labels)
                boolean allBuildSuccessful=true
                for (Map item: buildOutput.values()){
                    if ('BuildConfig'.equalsIgnoreCase(item.kind)){
                        Map build=buildOutput["Build/${item.metadata.name}-${item.status.lastVersion}"]
                        if (!isBuildSuccesful(build)){
                            allBuildSuccessful=false
                            break;
                        }
                    }
                }
                if (!allBuildSuccessful){
                    script.error('Sorry, not all builds have been successful! :`(')
                }

                openshift.selector( 'is', labels).withEach {
                    def iso=it.object()

                    buildOutput["${key(iso)}"] = [
                            'kind': iso.kind,
                            'metadata': ['name':iso.metadata.name, 'namespace':iso.metadata.namespace],
                            'labels':iso.metadata.labels
                    ]
                    String baseName=getImageStreamBaseName(iso)
                    buildOutput["BaseImageStream/${baseName}"]=['ImageStream':key(iso)]
                }

                __context['build'] = ['status':buildOutput, 'projectName':"${openshift.project()}"]


            }
        }
    }

    private def applyBuildConfig(CpsScript script, OpenShiftDSL openshift, String appName, String envName, Map models, Map currentModels) {
        def bcSelector = ['app-name': appName, 'env-name': envName]

        if (logLevel >= 4 ) script.echo "openShiftApplyBuildConfig:openshift1:${openshift.dump()}"


        script.echo "Processing ${models.size()} objects for '${appName}' for '${envName}'"
        def creations=[]
        def updates=[]
        def patches=[]

        for (Object o : models.values()) {
            if (logLevel >= 4 ) script.echo "Processing '${o.kind}/${o.metadata.name}' (before apply)"
            if (o.metadata.labels==null) o.metadata.labels =[:]
            o.metadata.labels["app"] = "${appName}-${envName}"
            o.metadata.labels["app-name"] = "${appName}"
            o.metadata.labels["env-name"] = "${envName}"

            def sel=openshift.selector("${o.kind}/${o.metadata.name}")
            if (sel.count()==0){
                //script.echo "Creating '${o.kind}/${o.metadata.name}'"
                creations.add(o)
            }else{
                if (!'ImageStream'.equalsIgnoreCase("${o.kind}")){
                    script.echo "Skipping '${key(o)}'"
                    //updates.add(o)
                    patches.add(o)
                }else{
                    //script.echo "Skipping '${o.kind}/${o.metadata.name}' (Already Exists)"
                    def newObject=o
                    if (newObject.spec && newObject.spec.tags){
                        newObject.spec.remove('tags')
                    }
                    //script.echo "Modified '${o.kind}/${o.metadata.name}' = ${newObject}"
                    updates.add(newObject)
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
            script.echo "Reusing '${buildNameSelector}'"
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
    private def isReplicationControllerComplete(rc) {
        String phase=rc.metadata.annotations['openshift.io/deployment.phase']
        return ("Complete".equalsIgnoreCase(phase) || "Cancelled".equalsIgnoreCase(phase) || "Failed".equalsIgnoreCase(phase) || "Error".equalsIgnoreCase(phase))
    }

    private def isBuildComplete(build) {
        return ("Complete".equalsIgnoreCase(build.status.phase) || "Cancelled".equalsIgnoreCase(build.status.phase) || "Failed".equalsIgnoreCase(build.status.phase) || "Error".equalsIgnoreCase(build.status.phase))
    }

    private def isBuildSuccesful(build) {
        return "Complete".equalsIgnoreCase(build.status.phase)
    }

    private def waitForBuildsWithSelector(CpsScript script, OpenShiftDSL openshift, selector) {
        def names=selector.names()
        if (names.size() > 0){
            for (String name:names){
                if (logLevel >= 3 ) script.echo "Checking status of '${name}'"
                openshift.selector(name).watch {
                    return isBuildComplete(it.object())
                }
            }
        }
    } // end method

    @NonCPS
    private def getImageStreamBaseName(res) {
        String baseName=res.metadata.name
        if (res.metadata && res.metadata.labels && res.metadata.labels['base-name']){
            baseName=res.metadata.labels['base-name']
        }
        return baseName
    }

    def deploy(CpsScript script, Map context) {
        //dcModels
        OpenShiftDSL openshift=script.openshift
        Map deployCfg = context.deploy
        Map buildCfg = context.build
        Map metadata = context.metadata

        if (!deployCfg.dcPrefix) deployCfg.dcPrefix=context.name
        if (!deployCfg.dcSuffix) deployCfg.dcSuffix="-${deployCfg.envName}"

        script.echo "Deploying to '${context.name}'"
        openshift.withCluster() {
            def buildProjectName="${openshift.project()}"
            def buildImageStreams=[:]

            //script.echo "buildImageStreams:${buildImageStreams}"
            openshift.withCredentials( 'jenkins-deployer-dev.token' ) {
                openshift.withProject( deployCfg.projectName ) {

                    //script.echo "DeployModels:${models}"
                    applyDeploymentConfig(script, openshift, context)


                } // end openshift.withProject()
            } // end openshift.withCredentials()
        } // end openshift.withCluster()
    } // end 'deploy' method

    @NonCPS
    private def jsonClone(Object object) {
        def jsonString = groovy.json.JsonOutput.toJson(object)
        return new groovy.json.JsonSlurper().parseText(jsonString)
    }

    @NonCPS
    private def toJson(String string) {
        return new groovy.json.JsonSlurper().parseText(string)
    }

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
                            if (logLevel >= 4 ) script.echo "${t.imageChangeParams.from}"
                            def dockerImageReference = ' '
                            def selector=openshift.selector("istag/${t.imageChangeParams.from.name}")

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

                            if (logLevel >= 4 ) script.echo "ImageReference is '${dockerImageReference}'"
                            c.image = "${dockerImageReference}"
                        }
                    }
                }
            }
        }
    }

    private def applyDeploymentConfig(CpsScript script, OpenShiftDSL openshift, Map context) {
        Map deployCtx = context.deploy
        def labels=['app-name':context.name, 'env-name':deployCtx.envName];
        def replicas=[:]

        Map initDeploymemtConfigStatus=loadDeploymentConfigStatus(openshift, labels)
        Map models = loadObjectsFromTemplate(openshift, context.dcModels, context)

        List upserts=[]
        for (Map m : models.values()) {
            if ('ImageStream'.equalsIgnoreCase(m.kind)){
                upserts.add(m)
            }
        }
        script.echo "Applying ImageStream"
        openshift.apply(upserts)
        //.label(['app':"${context['app-name']}-${context['env-name']}", 'app-name':context['app-name'], 'env-name':context['env-name']], "--overwrite")
        for (Map m : upserts) {
            String sourceImageStreamKey=context.build.status["BaseImageStream/${getImageStreamBaseName(m)}"]['ImageStream']
            Map sourceImageStream = context.build.status[sourceImageStreamKey]
            String sourceImageStreamRef="${sourceImageStream.metadata.namespace}/${sourceImageStream.metadata.name}:latest"
            String targetImageStreamRef="${m.metadata.name}:${labels['env-name']}"

            script.echo "Tagging '${sourceImageStreamRef}' as '${targetImageStreamRef}'"
            openshift.tag(sourceImageStreamRef, targetImageStreamRef)
        }

        script.echo "Applying Configurations"
        openshift.apply(models.values()).label(['app':"${context['app-name']}-${context['env-name']}", 'app-name':context['app-name'], 'env-name':context['env-name']], "--overwrite")
        waitForDeploymentsToComplete(script, openshift, labels)
    }

    private Map loadDeploymentConfigStatus(OpenShiftDSL openshift, Map labels){
        Map buildOutput = [:]
        def selector=openshift.selector('dc', labels)

        if (selector.count()>0) {
            for (Map dc : selector.objects()) {
                String rcName = "ReplicationController/${dc.metadata.name}-${dc.status.latestVersion}"
                Map rc = openshift.selector(rcName).object()
                buildOutput[rcName] = [
                        'kind': rc.kind,
                        'metadata': ['name':rc.metadata.name],
                        'status': rc.status,
                        'phase': rc.metadata.annotations['openshift.io/deployment.phase']
                ]

                buildOutput["${key(dc)}"] = [
                        'kind': dc.kind,
                        'metadata': ['name':dc.metadata.name],
                        'status': ['latestVersion':dc.status.latestVersion, 'latestReplicationControllerName':rcName]
                ]
            }
        }
        return buildOutput
    }
} // end class
