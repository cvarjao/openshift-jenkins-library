package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;

class OpenShiftHelper {
  
  def build(CpsScript script, Map __context) {
      script.echo "openShiftBuild:openshift1:${script.openshift.dump()}"
      script.openshift.withCluster() {
        script.echo "openShiftBuild:openshift2:${script.openshift.dump()}"
        script.openshift.withProject(script.openshift.project()) {
          def models=[];

          script.echo "openShiftBuild:openshift3:${script.openshift.dump()}"
          script.echo "openShiftBuild: project:${script.openshift.project()}"

          if (__context.models!=null){
            __context.models.resolveStrategy = Closure.DELEGATE_FIRST;
            __context.models.delegate = this;
            models=__context.models();
          }


          script.echo "openShiftBuild: models:${models.dump()}"

          script.echo 'Processing template ...'
          
          applyBuildConfig(script, __context.metadata.appName, __context.metadata.buildEnvName, models);

          script.echo 'Creating/Updating Objects (from template)'
          //def builds=[];
          //builds.add(OpenShiftHelper.startBuild(openshift, ['app-name':metadata.appName, 'env-name':metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
          //openShiftWaitForBuilds(openshift, builds)
        }
      }
  }
  
  def applyBuildConfig(CpsScript script, appName, envName, models) {
    //def body = {
      script.echo "OpenShiftHelper.applyBuildConfig: Hello - ${script.dump()}"
      script.echo "openShiftBuild:openshift2:${script.openshift.dump()}"
    //} //end 'body 'closure
    
    def bcSelector=['app-name':appName, 'env-name':envName];

    script.echo "openShiftApplyBuildConfig:openshift1:${script.openshift.dump()}"

    script.echo "Cancelling all pending builds"
    script.openshift.selector( 'bc', bcSelector).cancelBuild();

    script.echo "Waiting for all pending builds to complete or cancel"
    script.openshift.selector( 'builds', bcSelector).watch {
        if ( it.count() == 0 ) return true
        def allDone = true
        it.withEach {
            def buildModel = it.object()
            if ( it.object().status.phase != "Complete" &&  it.object().status.phase != "Failed") {
                allDone = false
            }
        }
        return allDone;
    }

    script.echo "Applying ${models.size()} objects for '${appName}' for '${envName}'"
    for ( o in models ) {
       script.echo "Processing '${o.kind}/${o.metadata.name}'"
        o.metadata.labels[ "app" ] = "${appName}-${envName}"
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
    script.openshift.apply(models);
    

    
    //body.resolveStrategy = Closure.DELEGATE_FIRST;
    //body.delegate = script;
    //body();
    
    //def bcSelector=['app-name':appName, 'env-name':envName];
    //echo "Cancelling all pending builds"
    //openshift.selector( 'bc', bcSelector).cancelBuild();
  }
}
