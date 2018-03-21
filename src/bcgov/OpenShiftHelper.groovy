package bcgov;

class OpenShiftHelper {
  static def build(script, String appName, String envName, Closure models) {
    null;
  }
  static def applyBuildConfig(script, String appName, String envName, Closure models) {
    //def bcSelector=['app-name':appName, 'env-name':envName];
    //echo "Cancelling all pending builds"
    //openshift.selector( 'bc', bcSelector).cancelBuild();
  }
}
