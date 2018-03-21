
def call(metadata, Map __context) {
  echo "openShiftBuild:openshift1:${openshift.dump()}"
  openshift.withCluster() {
    echo "openShiftBuild:openshift2:${openshift.dump()}"
    openshift.withProject(openshift.project()) {
      echo "openShiftBuild:openshift3:${openshift.dump()}"
      echo "openShiftBuild: project:${openshift.project()}"
      
      def models=__context.models();
      echo "openShiftBuild: models:${models.dump()}"
      
      echo 'Processing template ...'
      openShiftApplyBuildConfig(metadata.appName, metadata.buildEnvName, models)

      echo 'Creating/Updating Objects (from template)'
      def builds=[];
      builds.add(openShiftStartBuild(openshift, ['app-name':metadata.appName, 'env-name':metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
      openShiftWaitForBuilds(openshift, builds)
    }
  }
}
