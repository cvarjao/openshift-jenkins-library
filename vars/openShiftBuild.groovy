
def call(metadata, Map __context) {
  openshift.withCluster() {
    openshift.withProject(openshift.project()) {
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
