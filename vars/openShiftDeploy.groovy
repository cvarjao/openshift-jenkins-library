
def call(metadata, Closure body) {
  def context= [:]
  
  if (body!=null){
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = context
    body()
  }
  
  echo "metadata: ${metadata.dump()}"
  echo "context: ${context.dump()}"
  
  context.dcPrefix=metadata.appName;
  context.dcSuffix='-dev';
//  context.envName=context.envName

  if (metadata.isPullRequest){
      context.envName = "pr-${metadata.pullRequestNumber}"
      context.dcSuffix="-pr-${metadata.pullRequestNumber}";
  }

  openshift.withCluster() {
      def buildProjectName="${openshift.project()}"
      def buildImageStreams=[:];
      openshift.selector( 'is', ['app-name':metadata.appName, 'env-name':metadata.buildEnvName]).withEach {
          buildImageStreams["${it.object().metadata.name}"]=true;
      }

      echo "buildImageStreams:${buildImageStreams}"
      openshift.withCredentials( 'jenkins-deployer-dev.token' ) {
          openshift.withProject( context.projectName ) {
          def models=[];

          if (context.models!=null){
            context.models.resolveStrategy = Closure.DELEGATE_FIRST
            context.models.delegate = this
            models = context.models();
          }
          
          echo "${models}"
          openShiftApplyDeploymentConfig(openshift, buildProjectName, metadata.appName, envName, models, buildImageStreams)

          } // end openshift.withProject()
      } // end openshift.withCredentials()
  } // end openshift.withCluster()
}
