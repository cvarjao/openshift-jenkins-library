
def call(metadata, Closure body) {
  def context= ['models':[]]
  
  if (body!=null){
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = context
    body()
  }
  
  openshift.withCluster() {
    echo "project:${openshift.project()}"
    echo "models:${context.models}"
  }
}
