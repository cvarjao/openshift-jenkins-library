
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
    if (context.models!=null){
      context.models.resolveStrategy = Closure.DELEGATE_FIRST
      context.models.delegate = this
      echo "models:${context.models()}"
    }
    
  }
}
