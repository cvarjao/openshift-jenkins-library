def call(_openshift, List builds) {
  //Wait for all builds to complete
  _openshift.selector(builds).watch {
      def build=it.object();
      def buildDone=("Complete".equalsIgnoreCase(build.status.phase) || "Cancelled".equalsIgnoreCase(build.status.phase))
      if (!buildDone){
          echo "Waiting for '${it.name()}' (${build.status.phase})"
      }
      return buildDone;
  }

  _openshift.selector(builds).withEach { build ->
      def bo = build.object(); // build object
      if (!"Complete".equalsIgnoreCase(bo.status.phase)){
          error "Build '${build.name()}' did not successfully complete (${bo.status.phase})"
      }
  }
}
