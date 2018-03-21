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
          
          applyBuildConfig(script);

          script.echo 'Creating/Updating Objects (from template)'
          //def builds=[];
          //builds.add(OpenShiftHelper.startBuild(openshift, ['app-name':metadata.appName, 'env-name':metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
          //openShiftWaitForBuilds(openshift, builds)
        }
      }
  }
  
  def applyBuildConfig(CpsScript script) {
    //def body = {
      script.echo "OpenShiftHelper.applyBuildConfig: Hello - ${script.dump()}"
      script.echo "openShiftBuild:openshift2:${script.openshift.dump()}"
    //} //end 'body 'closure
    
    //body.resolveStrategy = Closure.DELEGATE_FIRST;
    //body.delegate = script;
    //body();
    
    //def bcSelector=['app-name':appName, 'env-name':envName];
    //echo "Cancelling all pending builds"
    //openshift.selector( 'bc', bcSelector).cancelBuild();
  }
}
