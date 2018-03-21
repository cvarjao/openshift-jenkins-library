
def call(_openshift, metadata, Map __context) {
  
  openshift.withCluster() {
    echo "project:${_openshift.project()}"
    echo "models:${__context.dump()}"
    def models=[];
    openshift.withProject(_openshift.project()) {
      if (__context.models!=null){
//        __context.models.resolveStrategy = Closure.DELEGATE_FIRST
//        __context.models.delegate = this
        models = __context.models();
      }

      echo 'Processing template ...'
      openShiftApplyBuildConfig(openshift, metadata.appName, metadata.buildEnvName, models)

      echo 'Creating/Updating Objects (from template)'
      def builds=[];
      builds.add(openShiftStartBuild(openshift, ['app-name':metadata.appName, 'env-name':metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
      openShiftWaitForBuilds(openshift, builds)
    }
  }
}
