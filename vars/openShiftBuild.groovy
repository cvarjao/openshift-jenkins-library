
def call(_openshift, metadata, Closure body) {
  def context= [:]
  
  if (body!=null){
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = context
    body()
  }
  
  _openshift.withCluster() {
    echo "project:${_openshift.project()}"
    echo "models:${context.dump()}"
    def models=[];
    _openshift.withProject(_openshift.project()) {
      if (context.models!=null){
        context.models.resolveStrategy = Closure.DELEGATE_FIRST
        context.models.delegate = this
        models = context.models();
      }

      echo 'Processing template ...'
      openShiftApplyBuildConfig(_openshift, metadata.appName, metadata.buildEnvName, models)

      echo 'Creating/Updating Objects (from template)'
      def builds=[];
      builds.add(openShiftStartBuild(_openshift, ['app-name':metadata.appName, 'env-name':metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
      openShiftWaitForBuilds(_openshift, builds)
    }
  }
}
