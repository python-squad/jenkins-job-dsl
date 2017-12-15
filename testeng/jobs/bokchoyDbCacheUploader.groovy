package testeng

import hudson.util.Secret
import org.yaml.snakeyaml.Yaml
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_MASKED_PASSWORD
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_LOG_ROTATOR
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_HIPCHAT
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_BASE_URL
import static org.edx.jenkins.dsl.JenkinsPublicConstants.JENKINS_PUBLIC_GITHUB_BASEURL

Map config = [:]
Binding bindings = getBinding()
config.putAll(bindings.getVariables())
PrintStream out = config['out']

try {
    out.println('Parsing secret YAML file')
    String constantsConfig = new File("${BOKCHOY_DB_CACHE_UPLOADER_SECRET}").text
    Yaml yaml = new Yaml()
    secretMap = yaml.load(constantsConfig)
    out.println('Successfully parsed secret YAML file')
}
catch (any) {
    out.println('Jenkins DSL: Error parsing secret YAML file')
    out.println('Exiting with error code 1')
    return 1
}

/*
Example secret YAML file used by this script
bokchoyDbCacheUploaderSecret:
    toolsTeam: [ member1, member2, ... ]
    repoName : name-of-github-edx-repo
    testengUrl: testeng-github-url-segment
    platformUrl : platform-github-url-segment
    testengCredential : n/a
    platformCredential : n/a
    platformCloneReference : clone/.git
    accessKeyId : 123abc
    secretAccessKey : 123abc
    hipchat : token
    refSpec : '+refs/heads/master:refs/remotes/origin/master'
    defaultBranch : 'master'
    defaultTestengBranch: 'master'
    region : us-east-1
    email : email-address@email.com
*/

// Iterate over the job configurations
secretMap.each { jobConfigs ->
    Map jobConfig = jobConfigs.getValue()

    assert jobConfig.containsKey('toolsTeam')
    assert jobConfig.containsKey('repoName')
    assert jobConfig.containsKey('testengUrl')
    assert jobConfig.containsKey('platformUrl')
    assert jobConfig.containsKey('testengCredential')
    assert jobConfig.containsKey('platformCredential')
    assert jobConfig.containsKey('platformCloneReference')
    assert jobConfig.containsKey('accessKeyId')
    assert jobConfig.containsKey('secretAccessKey')
    assert jobConfig.containsKey('region')
    assert jobConfig.containsKey('refSpec')
    assert jobConfig.containsKey('defaultBranch')
    assert jobConfig.containsKey('defaultTestengBranch')
    assert jobConfig.containsKey('email')
    assert jobConfig.containsKey('hipchat')

    job('bokchoy_db_cache_uploader') {

        description('Check to see if a merge introduces new migrations, and create a PR into edx-platform if it does.')

        // Enable project security to avoid exposing aws keys
        authorization {
            blocksInheritance(true)
            jobConfig['toolsTeam'].each { member ->
                permissionAll(member)
            }
        }

        logRotator JENKINS_PUBLIC_LOG_ROTATOR()
        label('jenkins-worker')
        concurrentBuild(false)

        multiscm {
           git { //using git on the branch and url, clone, clean before checkout
                remote {
                    url(JENKINS_PUBLIC_GITHUB_BASEURL + jobConfig['platformUrl'] + '.git')
                    refspec(jobConfig['refSpec'])
                }
                branch(jobConfig['defaultBranch'])
                browser()
                extensions {
                    cloneOptions {
                        reference("\$HOME/" + jobConfig['platformCloneReference'])
                        timeout(10)
                    }
                    cleanBeforeCheckout()
                    relativeTargetDirectory(jobConfig['repoName'])
                }
            }
            git { //using git on the branch and url, clean before checkout
                remote {
                    url(JENKINS_PUBLIC_GITHUB_BASEURL + jobConfig['testengUrl'] + '.git')
                }
                branch(jobConfig['defaultTestengBranch'])
                browser()
                extensions {
                    cleanBeforeCheckout()
                    relativeTargetDirectory('testeng-ci')
                }
            }
        }
        triggers {
            // Trigger jobs via github pushes
            githubPush()
        }

        // Put sensitive info into masked password slots
        configure { project ->
            project / buildWrappers << 'EnvInjectPasswordWrapper' {
                injectGlobalPasswords false
                maskPasswordParameters true
                passwordEntries {
                    EnvInjectPasswordEntry {
                        name 'AWS_ACCESS_KEY_ID'
                        value Secret.fromString(jobConfig['accessKeyId']).getEncryptedValue()
                    }
                    EnvInjectPasswordEntry {
                        name 'AWS_SECRET_ACCESS_KEY'
                        value Secret.fromString(jobConfig['secretAccessKey']).getEncryptedValue()
                    }
                }
            }
        }

        environmentVariables {
            env('AWS_DEFAULT_REGION', jobConfig['region'])
        }

        wrappers {
            timestamps()
        }

        steps {
            shell(readFileFromWorkspace('testeng/resources/bokchoy-db-cache-script.sh'))
        }

        Map <String, String> predefinedPropsMap  = [:]
        predefinedPropsMap.put('GIT_SHA', '${GIT_COMMIT}')
        predefinedPropsMap.put('GITHUB_REPO', jobConfig['repoName'])

        publishers { //JUnit Test report, trigger GitHub-Build-Status, email, message hipchat
            mailer(jobConfig['email'])
            hipChat JENKINS_PUBLIC_HIPCHAT.call(jobConfig['hipchat'])
        }
    }
}
