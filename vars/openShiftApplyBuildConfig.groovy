
def call(String appName, String envName, List models) {
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
