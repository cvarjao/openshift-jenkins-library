
def call(metadata, Closure body) {
  def context= [:]
  
  if (body!=null){
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = context
    body()
  }
  
  openshift.withCluster() {
    echo "project:${openshift.project()}"
    echo "models:${context.dump()}"
    def models=[];
    
    if (context.models!=null){
      context.models.resolveStrategy = Closure.DELEGATE_FIRST
      context.models.delegate = this
      models = context.models();
    }
    
    echo 'Processing template ...'
    openShiftApplyBuildConfig(openshift, metadata.appName, metadata.buildEnvName, models)

    echo 'Creating/Updating Objects (from template)'
    def builds=[];
    builds.add(openShiftStartBuild(openshift, ['app-name':metadata.appName, 'env-name':metadata.buildEnvName], "${metadata.modules['spring-petclinic'].commit}"));
    openShiftWaitForBuilds(openshift, builds)
  }
}
