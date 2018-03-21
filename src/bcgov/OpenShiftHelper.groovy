package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;

class OpenShiftHelper {
  
  static def build(CpsScript script) {
    def body = {
      echo "openShiftBuild:openshift1:${openshift.dump()}"
      openshift.withCluster() {
        echo "openShiftBuild:openshift2:${openshift.dump()}"
        openshift.withProject(openshift.project()) {
          def models=[];

          echo "openShiftBuild:openshift3:${openshift.dump()}"
          echo "openShiftBuild: project:${openshift.project()}"

          if (__context.models!=null){
            __context.models.resolveStrategy = Closure.DELEGATE_FIRST;
            __context.models.delegate = this;
            models=__context.models();
          }


          echo "openShiftBuild: models:${models.dump()}"

          echo 'Processing template ...'
          OpenShiftHelper.applyBuildConfig(this);

          echo 'Creating/Updating Objects (from template)'
          //def builds=[];
          //builds.add(OpenShiftHelper.startBuild(openshift, ['app-name':metadata.appName, 'env-name':metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
          //openShiftWaitForBuilds(openshift, builds)
        }
      }
    }
    body.resolveStrategy = Closure.DELEGATE_FIRST;
    body.delegate = script;
    body();
    //script.echo "OpenShiftHelper.build: Hello"
  }
  static def applyBuildConfig(CpsScript script) {
    script.echo "OpenShiftHelper.applyBuildConfig: Hello - ${script.dump()}"
    //def bcSelector=['app-name':appName, 'env-name':envName];
    //echo "Cancelling all pending builds"
    //openshift.selector( 'bc', bcSelector).cancelBuild();
  }
}
