
def call(String appName, String envName, List models) {
  def bcSelector=['app-name':appName, 'env-name':envName];
  
  echo "Cancelling all pending builds"
  openshift.selector( 'bc', bcSelector).cancelBuild();
  
  echo "Waiting for all pending builds to complete or cancel"
  openshift.selector( 'builds', bcSelector).watch {
      if ( it.count() == 0 ) return true
      def allDone = true
      it.withEach {
          def buildModel = it.object()
          if ( it.object().status.phase != "Complete" &&  it.object().status.phase != "Failed") {
              allDone = false
          }
      }
      return allDone;
  }
  
  echo "Applying ${models.size()} objects for '${appName}' for '${envName}'"
  for ( o in models ) {
     echo "Processing '${o.kind}/${o.metadata.name}'"
      o.metadata.labels[ "app" ] = "${appName}-${envName}"
      /*
      def sel=openshift.selector("${o.kind}/${o.metadata.name}");
      if (sel.count()==0){
          echo "Creating '${o.kind}/${o.metadata.name}"
          openshift.create([o]);
      }else{
          echo "Patching '${o.kind}/${o.metadata.name}"
          openshift.apply(o);
      }
      */
  }
  openshift.apply(models);
}
