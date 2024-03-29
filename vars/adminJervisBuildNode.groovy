import hudson.model.Run
import hudson.plugins.git.util.BuildData
import jenkins.scm.api.SCMHead
import net.gleske.jervis.lang.PipelineGenerator
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty

@NonCPS
Map getDockerOptions(Map settings) {
    PipelineGenerator pipeline_generator

    if(('pipeline_generator' in settings) && (settings.pipeline_generator in PipelineGenerator)) {
        pipeline_generator = settings.pipeline_generator
    } else {
        pipeline_generator = getUserBinding('jervis_global_pipeline_generator')
    }

    Map options = [
        image: 'jervis-agent-ubuntu2204',
        args: [
            '--cap-add=NET_ADMIN',
            '--group-add sudo'
        ]
    ]

    if('docker' in pipeline_generator?.yaml) {
        options.volume = "docker-data-${UUID.randomUUID()}"
        options.args << "--privileged -v ${options.volume}:/var/lib/docker"
        options.args << '--group-add docker'
    }

    options
}

/**
  After initial SCM checkout this method will return the commit hash which is
  the tip of `checkout scm` step.
  */
@NonCPS
String getHeadCommit(Run build) {
    String jobBaseName = build.parent.name
    def buildData = build?.actions?.find {
        it in BuildData &&
        jobBaseName in it.buildsByBranchName
    }
    if(buildData) {
        buildData.buildsByBranchName[jobBaseName].SHA1.name
    }
    else  {
        ''
    }
}

/**
  Use pull request metadata to resolve the target job for evaluating git forensics
  */
@NonCPS
String getReferenceJob() {
    SCMHead head = currentBuild.rawBuild.parent?.getProperty(BranchJobProperty)?.branch?.head
    if(!(head in PullRequestSCMHead) || !head?.target?.name) {
        return ''
    }
    String targetBranch = head.target.name
    if(!(targetBranch in currentBuild.rawBuild.parent.parent.items*.name)) {
        return ''
    }
    // return the target reference job
    [currentBuild.rawBuild.parent.parent.fullName, targetBranch].join('/')

}

def setupEnv() {
    if(!env.HEAD_LONG_COMMIT) {
        env.HEAD_LONG_COMMIT = getHeadCommit(currentBuild.rawBuild)
        if(env.HEAD_LONG_COMMIT) {
            env.HEAD_SHORT_COMMIT = env.HEAD_LONG_COMMIT.substring(0, 7)
            env.HASH_VERSION = "${env.JOB_BASE_NAME}-${env.HEAD_SHORT_COMMIT}"
            env.RELEASE_VERSION = (env.TAG_NAME) ? env.TAG_NAME : env.HASH_VERSION
        }
        String referenceJob = getReferenceJob()
        if(referenceJob) {
            referenceJob = currentBuild.rawBuild.parent.fullName
        }
        discoverReferenceBuild referenceJob: referenceJob
    }
}

def call(Map settings, Closure body) {
    Map dockerCLI = getDockerOptions()
    node('built-in') {
        if(dockerCLI.volume) {
            sh "docker volume create ${dockerCLI.volume}"
        }
        try {
            docker.image(dockerCLI.image).inside(dockerCLI.args.join(' ')) {
                checkout scm
				setupEnv()
                body()
            }
        } finally {
            // volume cleanup
            if(dockerCLI.volume) {
                sh "docker volume rm -f ${dockerCLI.volume}"
            }
            // workspace cleanup
            deleteDir()
        }
    }
}

def call(Map additional_settings, Map settings, Closure body) {
    call(settings + additional_settings, body)
}

// Used by upstream Jervis
def call(PipelineGenerator pipeline_generator, String labels, Closure body) {
    call([pipeline_generator: pipeline_generator], body)
}
