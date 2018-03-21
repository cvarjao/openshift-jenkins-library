package bcgov;

import org.jenkinsci.plugins.workflow.cps.CpsScript;

class OpenShiftHelper {
  static def build(CpsScript script, String appName, String envName, Closure models) {
    script.echo "OpenShiftHelper.build: Hello"
  }
  static def applyBuildConfig(script, String appName, String envName, Closure models) {
    script.echo "OpenShiftHelper.applyBuildConfig: Hello"
    //def bcSelector=['app-name':appName, 'env-name':envName];
    //echo "Cancelling all pending builds"
    //openshift.selector( 'bc', bcSelector).cancelBuild();
  }
}
