
def call(metadata) {
  openshift.withCluster() {
    echo "project:${openshift.porject()}"
  }
}
