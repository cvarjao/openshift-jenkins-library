
def updateContainerImages(_openshift, containers, triggers) {
    for ( c in containers ) {
        for ( t in triggers) {
            if ('ImageChange'.equalsIgnoreCase(t['type'])){
                for ( cn in t.imageChangeParams.containerNames){
                    if (cn.equalsIgnoreCase(c.name)){
                        echo "${t.imageChangeParams.from}"
                        def dockerImageReference = '';
                        def selector=_openshift.selector("istag/${t.imageChangeParams.from.name}");

                        if (t.imageChangeParams.from['namespace']!=null && t.imageChangeParams.from['namespace'].length()>0){
                            _openshift.withProject(t.imageChangeParams.from['namespace']) {
                                selector=_openshift.selector("istag/${t.imageChangeParams.from.name}");
                                if (selector.count() == 1 ){
                                    dockerImageReference=selector.object().image.dockerImageReference
                                }
                            }
                        }else{
                            selector=_openshift.selector("istag/${t.imageChangeParams.from.name}");
                            if (selector.count() == 1 ){
                                dockerImageReference=selector.object().image.dockerImageReference
                            }
                        }

                        echo "ImageReference is '${dockerImageReference}'"
                        c.image = "${dockerImageReference}";
                    }
                }
            }
        }
    }
}

def call(_openshift, String buildProjectName, String appName, String envName, List models, buildImageStreams) {
    def dcSelector=['app-name':appName, 'env-name':envName];
    for ( m in models ) {
      if ("DeploymentConfig".equals(m.kind)){
          //m.spec.replicas = 0
          m.spec.paused = true
          updateContainerImages(_openshift, m.spec.template.spec.containers, m.spec.triggers);
      }
    }

    //echo "Scaling down"
    // _openshift.selector( 'dc', dcSelector).scale('--replicas=0', '--timeout=2m')
    selector.narrow('dc').withEach { dc ->
        def o = dc.object();
        echo "'${dc.name()}'  paused=${o.spec.paused}"
        if ( o.spec.paused == false ){
            dc.rollout().pause()
        }
    }
    
  echo "The template will create/update ${models.size()} objects"
  //TODO: needs to review usage of 'apply' it recreates Secrets!!!
  def selector=_openshift.apply(models);
  selector.label(['app':"${appName}-${envName}", 'app-name':"${appName}", 'env-name':"${envName}"], "--overwrite")

  selector.narrow('is').withEach { imageStream ->
      def o=imageStream.object();
      def imageStreamName="${o.metadata.name}"

      if (buildImageStreams[imageStreamName] != null ){
          echo "Tagging '${buildProjectName}/${o.metadata.name}:latest' as '${o.metadata.name}:${envName}'"
          _openshift.tag("${buildProjectName}/${o.metadata.name}:latest", "${o.metadata.name}:${envName}")
      }
  }
    
    selector.narrow('dc').withEach { dc ->
        def o = dc.object();
        echo "'${dc.name()}'  paused=${o.spec.paused}"
        if (o.spec.paused == true){
            dc.rollout().resume()
        }
    }
    
    
  //openshift.selector("dc/nginx").rollout().resume()
    
  //_openshift.selector( 'dc', dcSelector).scale('--replicas=0', '--timeout=2m')
 // _openshift.selector( 'dc', dcSelector).deploy('--cancel=true')
    echo "deploy:\n${_openshift.selector( 'dc', dcSelector).deploy()}"
  //_openshift.selector( 'dc', dcSelector).scale('--replicas=1', '--timeout=4m')
}
