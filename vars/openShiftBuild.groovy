
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
    echo "models:${context.models()}"
  }
}
