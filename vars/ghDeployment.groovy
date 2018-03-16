def call(String environmentName = 'PREVIEW') {
  def gitRepoUrl = scm.getUserRemoteConfigs()[0].getUrl()
  def gitRepoFullName=gitRepoUrl.replace('https://github.com/', '').replace('.git', '')
  echo "gitRepoFullName='${gitRepoFullName}'"
  withCredentials([usernamePassword(credentialsId: 'github-account', passwordVariable: 'githubPassword', usernameVariable: 'githubUsername')]) {
      def github=new GitHubBuilder().withPassword(githubUsername, githubPassword).build()
      def ghRepo=github.getRepository(gitRepoFullName);
      def ghDeploymentResponse=ghRepo.createDeployment(gitCommitId).environment(environmentName).description("Preview deployment").requiredContexts([]).create();
      ghDeploymentResponse.createStatus(GHDeploymentState.SUCCESS).targetUrl("http://somewhere.here.com").description("Preview deplyment2").create();
  }
}
