
def call(String appName, String envName, List models) {
  for ( o in models ) {
      o.metadata.labels[ "app" ] = "${appName}-${buildEnvName}"
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
