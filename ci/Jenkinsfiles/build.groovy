/*
 * (C) Copyright 2019 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Antoine Taillefer <ataillefer@nuxeo.com>
 *     Thomas Roger <troger@nuxeo.com>
 */

dockerNamespace = 'nuxeo'
kubernetesNamespace = 'platform'
repositoryUrl = 'https://github.com/nuxeo/nuxeo'
testEnvironments = [
  'dev',
  'mongodb',
  'postgresql',
]

properties([
  [$class: 'GithubProjectProperty', projectUrlStr: repositoryUrl],
  [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '60', numToKeepStr: '60', artifactNumToKeepStr: '5']],
  disableConcurrentBuilds(),
])

void setGitHubBuildStatus(String context, String message, String state) {
  if (env.DRY_RUN != "true") {
    step([
      $class: 'GitHubCommitStatusSetter',
      reposSource: [$class: 'ManuallyEnteredRepositorySource', url: repositoryUrl],
      contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: context],
      statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: message, state: state]]],
    ])
  }
}

String getMavenArgs() {
  def args = '-B -nsu -Dnuxeo.skip.enforcer=true'
  if (!isPullRequest()) {
    args += ' -Prelease'
  }
  return args
}

String getMavenFailArgs() {
  return (isPullRequest() && pullRequest.labels.contains('failatend')) ? '--fail-at-end' : ' '
}

String getMavenJavadocArgs() {
  return isPullRequest() ? ' ' : '-Pjavadoc'
}

def isPullRequest() {
  return BRANCH_NAME =~ /PR-.*/
}

String getVersion() {
  return isPullRequest() ? getPullRequestVersion() : getReleaseVersion()
}

String getReleaseVersion() {
  String nuxeoVersion = readMavenPom().getVersion()
  String noSnapshot = nuxeoVersion.replace('-SNAPSHOT', '')
  String version = noSnapshot + '.1' // first version ever

  // find the latest tag if any
  sh "git fetch origin 'refs/tags/v${noSnapshot}*:refs/tags/v${noSnapshot}*'"
  def tag = sh(returnStdout: true, script: "git tag --sort=taggerdate --list 'v${noSnapshot}*' | tail -1 | tr -d '\n'")
  if (tag) {
    container('maven') {
      version = sh(returnStdout: true, script: "semver bump patch ${tag} | tr -d '\n'")
    }
  }
  return version
}

String getPullRequestVersion() {
  return "${BRANCH_NAME}-" + readMavenPom().getVersion()
}

String getDockerTagFrom(String version) {
  return version.tokenize('.')[0] + '.x'
}

void runFunctionalTests(String baseDir) {
  try {
    retry(2) {
      sh "mvn ${MAVEN_ARGS} ${MAVEN_FAIL_ARGS} -f ${baseDir}/pom.xml verify"
    }
  } catch(err) {
    echo "${baseDir} functional tests error: ${err}"
    throw err
  } finally {
    try {
      archiveArtifacts allowEmptyArchive: true, artifacts: "${baseDir}/**/target/failsafe-reports/*, ${baseDir}/**/target/**/*.log, ${baseDir}/**/target/*.png, ${baseDir}/**/target/*.html, ${baseDir}/**/target/**/distribution.properties, ${baseDir}/**/target/**/configuration.properties"
    } catch (err) {
      echo hudson.Functions.printThrowable(err)
    }
  }
}

void dockerPull(String image) {
  sh "docker pull ${image}"
}

void dockerRun(String image, String command, String user = null) {
  String userOption = user ? "--user=${user}" : ''
  sh "docker run --rm ${userOption} ${image} ${command}"
}

void dockerTag(String image, String tag) {
  sh "docker tag ${image} ${tag}"
}

void dockerPush(String image) {
  sh "docker push ${image}"
}

void dockerDeploy(String dockerRegistry, String imageName) {
  String fullImageName = "${dockerNamespace}/${imageName}"
  String fixedVersionInternalImage = "${DOCKER_REGISTRY}/${fullImageName}:${VERSION}"
  String latestInternalImage = "${DOCKER_REGISTRY}/${fullImageName}:${DOCKER_TAG}"
  String fixedVersionPublicImage = "${dockerRegistry}/${fullImageName}:${VERSION}"
  String latestPublicImage = "${dockerRegistry}/${fullImageName}:${DOCKER_TAG}"

  dockerPull(fixedVersionInternalImage)
  echo "Push ${latestInternalImage}"
  dockerTag(fixedVersionInternalImage, latestInternalImage)
  dockerPush(latestInternalImage)
  echo "Push ${fixedVersionPublicImage}"
  dockerTag(fixedVersionInternalImage, fixedVersionPublicImage)
  dockerPush(fixedVersionPublicImage)
  echo "Push ${latestPublicImage}"
  dockerTag(fixedVersionInternalImage, latestPublicImage)
  dockerPush(latestPublicImage)
}

def helmAddRepository(name, url) {
  sh "helm3 repo add ${name} ${url}"
}

def helmAddBitnamiRepository() {
  helmAddRepository("${BITNAMI_CHART_REPOSITORY_NAME}", "${BITNAMI_CHART_REPOSITORY_URL}")
}

def helmAddElasticRepository() {
  helmAddRepository("${ELASTIC_CHART_REPOSITORY_NAME}", "${ELASTIC_CHART_REPOSITORY_URL}")
}

def helmAddNuxeoRepository() {
  helmAddRepository("${NUXEO_CHART_REPOSITORY_NAME}", "${NUXEO_CHART_REPOSITORY_URL}")
}

def helmUpgrade(release, repository, chart, version, namespace, values) {
  sh """
    helm3 upgrade ${release} ${repository}/${chart} \
      --install \
      --version=${version} \
      --namespace=${namespace} \
      --values=${values}
  """
}

def helmUpgradeRedis(namespace) {
  helmUpgrade("${REDIS_CHART_NAME}", "${BITNAMI_CHART_REPOSITORY_NAME}", "${REDIS_CHART_NAME}", "${REDIS_CHART_VERSION}", namespace, "${HELM_VALUES_DIR}/values-redis.yaml~gen")
}

def helmUpgradeMongoDB(namespace) {
  helmUpgrade("${MONGODB_CHART_NAME}", "${BITNAMI_CHART_REPOSITORY_NAME}", "${MONGODB_CHART_NAME}", "${MONGODB_CHART_VERSION}", namespace, "${HELM_VALUES_DIR}/values-mongodb.yaml~gen")
}

def helmUpgradePostgreSQL(namespace) {
  helmUpgrade("${POSTGRESQL_CHART_NAME}", "${BITNAMI_CHART_REPOSITORY_NAME}", "${POSTGRESQL_CHART_NAME}", "${POSTGRESQL_CHART_VERSION}", namespace, "${HELM_VALUES_DIR}/values-postgresql.yaml~gen")
}

def helmUpgradeElasticsearch(namespace) {
  helmUpgrade("${ELASTICSEARCH_CHART_NAME}", "${ELASTIC_CHART_REPOSITORY_NAME}", "${ELASTICSEARCH_CHART_NAME}", "${ELASTICSEARCH_CHART_VERSION}", namespace, "${HELM_VALUES_DIR}/values-elasticsearch.yaml~gen")
}

def helmUpgradeKafka(namespace) {
  helmUpgrade("${KAFKA_CHART_NAME}", "${BITNAMI_CHART_REPOSITORY_NAME}", "${KAFKA_CHART_NAME}", "${KAFKA_CHART_VERSION}", namespace, "${HELM_VALUES_DIR}/values-kafka.yaml~gen")
}

def helmUninstall(release, namespace) {
  sh "helm3 uninstall ${release} --namespace=${namespace}"
}

def helmUninstallRedis(namespace) {
  helmUninstall("${REDIS_CHART_NAME}", namespace)
}

def helmUninstallMongoDB(namespace) {
  helmUninstall("${MONGODB_CHART_NAME}", namespace)
}

def helmUninstallPostgreSQL(namespace) {
  helmUninstall("${POSTGRESQL_CHART_NAME}", namespace)
}

def helmUninstallElasticsearch(namespace) {
  helmUninstall("${ELASTICSEARCH_CHART_NAME}", namespace)
}

def helmUninstallKafka(namespace) {
  helmUninstall("${KAFKA_CHART_NAME}", namespace)
}

def helmGenerateValues(directory, persistence, usage) {
  sh """
    for valuesFile in ${directory}/*.yaml; do
      PERSISTENCE=${persistence} USAGE=${usage} envsubst < \$valuesFile > \$valuesFile~gen
    done
  """
}

def rolloutStatus(kind, name, timeout, namespace) {
  sh """
    kubectl rollout status ${kind} ${name} \
      --timeout=${timeout} \
      --namespace=${namespace}
  """
}

def rolloutStatusRedis(namespace) {
  rolloutStatus('statefulset', "${TEST_REDIS_K8S_OBJECT}", "${TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT}", namespace)
}

def rolloutStatusMongoDB(namespace) {
  rolloutStatus('deployment', "${TEST_MONGODB_K8S_OBJECT}", "${TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT}", namespace)
}

def rolloutStatusPostgreSQL(namespace) {
  rolloutStatus('statefulset', "${TEST_POSTGRESQL_K8S_OBJECT}", "${TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT}", namespace)
}

def rolloutStatusElasticsearch(namespace) {
  rolloutStatus('statefulset', "${TEST_ELASTICSEARCH_K8S_OBJECT}", "${TEST_LONG_ROLLOUT_STATUS_TIMEOUT}", namespace)
}

def rolloutStatusKafka(namespace) {
  rolloutStatus('statefulset', "${TEST_KAFKA_K8S_OBJECT}", "${TEST_LONG_ROLLOUT_STATUS_TIMEOUT}", namespace)
}

def buildUnitTestStage(env) {
  def isDev = env == 'dev'
  def testNamespace = "${TEST_NAMESPACE_PREFIX}-${env}"
  def redisHost = "${TEST_REDIS_K8S_OBJECT}.${testNamespace}.${TEST_SERVICE_DOMAIN_SUFFIX}"
  def kafkaHost = "${TEST_KAFKA_K8S_OBJECT}.${testNamespace}.${TEST_SERVICE_DOMAIN_SUFFIX}:${TEST_KAFKA_PORT}"
  return {
    stage("Run ${env} unit tests") {
      container("maven-${env}") {
        script {
          setGitHubBuildStatus("utests/${env}", "Unit tests - ${env} environment", 'PENDING')
          try {
            sh "kubectl create namespace ${testNamespace}"
            try {
              echo """
              ----------------------------------------
              Run ${env} unit tests
              ----------------------------------------"""

              echo "${env} unit tests: install external services"
              // add chart repositories
              helmAddBitnamiRepository()
              helmAddElasticRepository()
              // substitute environment variables in chart values
              helmGenerateValues("${HELM_VALUES_DIR}", false, 'utests')
              // install external service charts
              helmUpgradeRedis(testNamespace)
              if (!isDev) {
                if (env == 'mongodb') {
                  helmUpgradeMongoDB(testNamespace)
                } else if (env == 'postgresql'){
                  helmUpgradePostgreSQL(testNamespace)
                }
                helmUpgradeElasticsearch(testNamespace)
                helmUpgradeKafka(testNamespace)
              }
              // wait for external services to be ready
              rolloutStatusRedis(testNamespace)
              if (!isDev) {
                if (env == 'mongodb') {
                  rolloutStatusMongoDB(testNamespace)
                } else {
                  rolloutStatusPostgreSQL(testNamespace)
                }
                rolloutStatusElasticsearch(testNamespace)
                rolloutStatusKafka(testNamespace)
              }

              echo "${env} unit tests: run Maven"
              if (isDev) {
                // empty file required by the read-project-properties goal of the properties-maven-plugin with the
                // customEnvironment profile
                sh "touch ${HOME}/nuxeo-test-${env}.properties"
              } else {
                // prepare test framework system properties
                sh """
                  cat ci/mvn/nuxeo-test-${env}.properties \
                    ci/mvn/nuxeo-test-elasticsearch.properties \
                    > ci/mvn/nuxeo-test-${env}.properties~gen
                  NAMESPACE=${testNamespace} DOMAIN=${TEST_SERVICE_DOMAIN_SUFFIX} \
                    envsubst < ci/mvn/nuxeo-test-${env}.properties~gen > ${HOME}/nuxeo-test-${env}.properties
                """
              }
              // run unit tests:
              // - in modules/core and dependent projects only (modules/runtime is run in a dedicated stage)
              // - for the given environment (see the customEnvironment profile in pom.xml):
              //   - in an alternative build directory
              //   - loading some test framework system properties
              def testCore = env == 'mongodb' ? 'mongodb' : 'vcs'
              def kafkaOptions = isDev ? '' : "-Pkafka -Dkafka.bootstrap.servers=${kafkaHost}"

              retry(2) {
                sh """
                  mvn ${MAVEN_ARGS} ${MAVEN_FAIL_ARGS} -rf :nuxeo-core-parent \
                    -Dcustom.environment=${env} \
                    -Dcustom.environment.log.dir=target-${env} \
                    -Dnuxeo.test.core=${testCore} \
                    -Dnuxeo.test.redis.host=${redisHost} \
                    ${kafkaOptions} \
                    test
                """
              }

              setGitHubBuildStatus("utests/${env}", "Unit tests - ${env} environment", 'SUCCESS')
            } catch(err) {
              echo "${env} unit tests error: ${err}"
              setGitHubBuildStatus("utests/${env}", "Unit tests - ${env} environment", 'FAILURE')
              throw err
            } finally {
              try {
                junit testResults: "**/target-${env}/surefire-reports/*.xml"
                if (!isDev) {
                  archiveKafkaLogs(testNamespace, "${env}-kafka.log")
                }
              } finally {
                echo "${env} unit tests: clean up test namespace"
                try {
                  // uninstall external service charts
                  if (!isDev) {
                    helmUninstallKafka(testNamespace)
                    helmUninstallElasticsearch(testNamespace)
                    if (env == 'mongodb') {
                      helmUninstallMongoDB(testNamespace)
                    } else {
                      helmUninstallPostgreSQL(testNamespace)
                    }
                  }
                  helmUninstallRedis(testNamespace)
                } finally {
                  // clean up test namespace
                  sh "kubectl delete namespace ${testNamespace} --ignore-not-found=true"
                }
              }
            }
          } catch(err) {
            // TODO NXP-29512: workaround to know which env is failing later on
            def errorMessage = "${env}: ${err.message}"
            echo "Throwing error with message: ${errorMessage}"
            error "${errorMessage}"
          }
        }
      }
    }
  }
}

void archiveKafkaLogs(namespace, logFile) {
  sh "kubectl logs ${TEST_KAFKA_POD_NAME} --namespace=${namespace} > ${logFile}"
  archiveArtifacts allowEmptyArchive: true, artifacts: "${logFile}"
}

pipeline {
  agent {
    label 'jenkins-nuxeo-platform-11'
  }
  options {
    timeout(time: 12, unit: 'HOURS')
  }
  environment {
    // force ${HOME}=/root - for an unexplained reason, ${HOME} is resolved as /home/jenkins though sh 'env' shows HOME=/root
    HOME = '/root'
    HELM_VALUES_DIR = 'ci/helm/values'
    BITNAMI_CHART_REPOSITORY_NAME = 'bitnami'
    BITNAMI_CHART_REPOSITORY_URL = 'https://charts.bitnami.com/bitnami'
    ELASTIC_CHART_REPOSITORY_NAME = 'elastic'
    ELASTIC_CHART_REPOSITORY_URL = 'https://helm.elastic.co/'
    NUXEO_CHART_REPOSITORY_NAME = 'nuxeo'
    NUXEO_CHART_REPOSITORY_URL = 'https://chartmuseum.platform.dev.nuxeo.com/'
    REDIS_CHART_NAME = 'redis'
    REDIS_CHART_VERSION = '11.2.1'
    MONGODB_CHART_NAME = 'mongodb'
    MONGODB_CHART_VERSION = '7.14.2'
    POSTGRESQL_CHART_NAME = 'postgresql'
    POSTGRESQL_CHART_VERSION = '9.8.4'
    ELASTICSEARCH_CHART_NAME = 'elasticsearch'
    ELASTICSEARCH_CHART_VERSION = '7.9.2'
    KAFKA_CHART_NAME = 'kafka'
    KAFKA_CHART_VERSION = '11.8.8'
    NUXEO_CHART_NAME = 'nuxeo'
    NUXEO_CHART_VERSION = '~2.0.0'
    USAGE = 'utests'
    TEST_NAMESPACE_PREFIX = "nuxeo-unit-tests-$BRANCH_NAME-$BUILD_NUMBER".toLowerCase()
    TEST_SERVICE_DOMAIN_SUFFIX = 'svc.cluster.local'
    TEST_REDIS_K8S_OBJECT = "${REDIS_CHART_NAME}-master"
    TEST_MONGODB_K8S_OBJECT = "${MONGODB_CHART_NAME}"
    TEST_POSTGRESQL_K8S_OBJECT = "${POSTGRESQL_CHART_NAME}-${POSTGRESQL_CHART_NAME}"
    TEST_ELASTICSEARCH_K8S_OBJECT = "${ELASTICSEARCH_CHART_NAME}-master"
    TEST_KAFKA_K8S_OBJECT = "${KAFKA_CHART_NAME}"
    TEST_KAFKA_PORT = '9092'
    TEST_KAFKA_POD_NAME = "${TEST_KAFKA_K8S_OBJECT}-0"
    TEST_DEFAULT_ROLLOUT_STATUS_TIMEOUT = '3m'
    // Elasticsearch and Kafka might take longer
    TEST_LONG_ROLLOUT_STATUS_TIMEOUT = '5m'
    NUXEO_IMAGE_NAME = 'nuxeo'
    MAVEN_OPTS = "$MAVEN_OPTS -Xms2g -Xmx3g -XX:+TieredCompilation -XX:TieredStopAtLevel=1"
    MAVEN_ARGS = getMavenArgs()
    MAVEN_FAIL_ARGS = getMavenFailArgs()
    MAVEN_JAVADOC_ARGS = getMavenJavadocArgs()
    VERSION = getVersion()
    DOCKER_TAG = getDockerTagFrom("${VERSION}")
    CHANGE_BRANCH = "${env.CHANGE_BRANCH != null ? env.CHANGE_BRANCH : BRANCH_NAME}"
    CHANGE_TARGET = "${env.CHANGE_TARGET != null ? env.CHANGE_TARGET : BRANCH_NAME}"
    CONNECT_PREPROD_URL = 'https://nos-preprod-connect.nuxeocloud.com/nuxeo'
    // used as a Helm release name and Kubernetes namespace, requires lower case alphanumeric characters
    PREVIEW_NAMESPACE = "nuxeo-preview-${BRANCH_NAME.toLowerCase()}"
    PREVIEW_HELM_RELEASE = 'nuxeo-preview'
    SLACK_CHANNEL = 'platform-notifs'
  }

  stages {
    stage('Set labels') {
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Set Kubernetes resource labels
          ----------------------------------------
          """
          echo "Set label 'branch: ${BRANCH_NAME}' on pod ${NODE_NAME}"
          sh """
            kubectl label pods ${NODE_NAME} branch=${BRANCH_NAME}
          """
          // output pod description
          echo "Describe pod ${NODE_NAME}"
          sh """
            kubectl describe pod ${NODE_NAME}
          """
        }
      }
    }

    stage('Update version') {
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Update version
          ----------------------------------------
          New version: ${VERSION}
          """
          sh """
            # root POM
            mvn ${MAVEN_ARGS} -Pdistrib,docker versions:set -DnewVersion=${VERSION} -DgenerateBackupPoms=false
            perl -i -pe 's|<nuxeo.platform.version>.*?</nuxeo.platform.version>|<nuxeo.platform.version>${VERSION}</nuxeo.platform.version>|' pom.xml
            perl -i -pe 's|org.nuxeo.ecm.product.version=.*|org.nuxeo.ecm.product.version=${VERSION}|' server/nuxeo-nxr-server/src/main/resources/templates/nuxeo.defaults

            # nuxeo-parent POM
            # only replace the first <version> occurence
            perl -i -pe '!\$x && s|<version>.*?</version>|<version>${VERSION}</version>| && (\$x=1)' parent/pom.xml

            # nuxeo-promote-packages POM
            # only replace the first <version> occurence
            perl -i -pe '!\$x && s|<version>.*?</version>|<version>${VERSION}</version>| && (\$x=1)' ci/release/pom.xml
          """
        }
      }
    }

    stage('Git commit') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Git commit
          ----------------------------------------
          """
          sh """
            git commit -a -m "Release ${VERSION}"
          """
        }
      }
    }

    stage('Build') {
      steps {
        setGitHubBuildStatus('maven/build', 'Build', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Compile
          ----------------------------------------"""
          echo "MAVEN_OPTS=$MAVEN_OPTS"
          sh "mvn ${MAVEN_ARGS} ${MAVEN_JAVADOC_ARGS} -V -T4C -DskipTests install"
          sh "mvn ${MAVEN_ARGS} ${MAVEN_JAVADOC_ARGS} -f server/pom.xml -DskipTests install"
        }
      }
      post {
        success {
          setGitHubBuildStatus('maven/build', 'Build', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('maven/build', 'Build', 'FAILURE')
        }
      }
    }

    stage('Run runtime unit tests') {
      steps {
        container('maven') {
          script {
            setGitHubBuildStatus('utests/runtime', 'Unit tests - runtime', 'PENDING')
            def testNamespace = "${TEST_NAMESPACE_PREFIX}-runtime"
            def redisHost = "${TEST_REDIS_K8S_OBJECT}.${testNamespace}.${TEST_SERVICE_DOMAIN_SUFFIX}"
            def kafkaHost = "${TEST_KAFKA_K8S_OBJECT}.${testNamespace}.${TEST_SERVICE_DOMAIN_SUFFIX}:${TEST_KAFKA_PORT}"
            sh "kubectl create namespace ${testNamespace}"
            try {
              echo """
              ----------------------------------------
              Run runtime unit tests
              ----------------------------------------"""

              echo 'runtime unit tests: install external services'
              // add chart repository
              helmAddBitnamiRepository()
              // substitute environment variables in chart values
              helmGenerateValues("${HELM_VALUES_DIR}", false, 'utests')
              // install external service charts
              helmUpgradeRedis(testNamespace)
              helmUpgradeKafka(testNamespace)
              // wait for external services to be ready
              rolloutStatusRedis(testNamespace)
              rolloutStatusKafka(testNamespace)

              echo 'runtime unit tests: run Maven'
              dir('modules/runtime') {
                retry(2) {
                  sh """
                    mvn ${MAVEN_ARGS} ${MAVEN_FAIL_ARGS} \
                      -Dnuxeo.test.redis.host=${redisHost} \
                      -Pkafka -Dkafka.bootstrap.servers=${kafkaHost} \
                      test
                  """
                }
              }

              setGitHubBuildStatus('utests/runtime', 'Unit tests - runtime', 'SUCCESS')
            } catch(err) {
              echo "runtime unit tests error: ${err}"
              setGitHubBuildStatus('utests/runtime', 'Unit tests - runtime', 'FAILURE')
              throw err
            } finally {
              try {
                junit testResults: '**/target/surefire-reports/*.xml'
                archiveKafkaLogs(testNamespace, 'runtime-kafka.log')
              } finally {
                echo 'runtime unit tests: clean up test namespace'
                try {
                  // uninstall external service charts
                  helmUninstallKafka(testNamespace)
                  helmUninstallRedis(testNamespace)
                } finally {
                  // clean up test namespace
                  sh "kubectl delete namespace ${testNamespace} --ignore-not-found=true"
                }
              }
            }
          }
        }
      }
    }

    stage('Run unit tests') {
      steps {
        script {
          def stages = [:]
          for (env in testEnvironments) {
            stages["Run ${env} unit tests"] = buildUnitTestStage(env);
          }
          try {
            parallel stages
          } catch (err) {
            // TODO NXP-29512: on a PR, make the build continue even if there is a test error
            // on other environments than the dev one
            // to remove when all test environments will be mandatory
            def errorMessage = err.message
            if (isPullRequest() && !errorMessage.startsWith('dev:')) {
              echo """
                Unit tests error: ${errorMessage}
                Waiting for NXP-29512, on a PR, the build continues even if there is a unit test error in other
                environments than the dev one.
              """
            } else {
              throw err
            }
          }
        }
      }
    }

    stage('Run server unit tests') {
      steps {
        setGitHubBuildStatus('utests/server', 'Unit tests - server', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Run server unit tests
          ----------------------------------------"""
          // run server tests
          dir('server') {
            sh "mvn ${MAVEN_ARGS} ${MAVEN_FAIL_ARGS} test"
          }
        }
      }
      post {
        success {
          setGitHubBuildStatus('utests/server', 'Unit tests - server', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('utests/server', 'Unit tests - server', 'FAILURE')
        }
      }
    }

    stage('Build Nuxeo Packages') {
      steps {
        setGitHubBuildStatus('packages/build', 'Build Nuxeo packages', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Package
          ----------------------------------------"""
          sh "mvn ${MAVEN_ARGS} -Dnuxeo.skip.enforcer=false -f packages/pom.xml -DskipTests install"
        }
      }
      post {
        success {
          setGitHubBuildStatus('packages/build', 'Build Nuxeo packages', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('packages/build', 'Build Nuxeo packages', 'FAILURE')
        }
      }
    }

    stage('Run "dev" functional tests') {
      steps {
        setGitHubBuildStatus('ftests/dev', 'Functional tests - dev environment', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Run "dev" functional tests
          ----------------------------------------"""
          runFunctionalTests('ftests')
        }
        findText regexp: '.*ERROR.*', fileSet: 'ftests/nuxeo-server-cmis-tests/**/log/server.log'
        findText regexp: '.*ERROR.*', fileSet: 'ftests/nuxeo-server-hotreload-tests/**/log/server.log'
        findText regexp: '.*ERROR.*', fileSet: 'ftests/nuxeo-server-tests/**/log/server.log'
        findText regexp: '.*ERROR.*', fileSet: 'ftests/nuxeo-jsf-to-web-ui-ftests/**/log/server.log'
      }
      post {
        always {
          junit testResults: '**/target/failsafe-reports/*.xml'
        }
        success {
          setGitHubBuildStatus('ftests/dev', 'Functional tests - dev environment', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('ftests/dev', 'Functional tests - dev environment', 'FAILURE')
        }
      }
    }

    stage('Build Docker image') {
      steps {
        setGitHubBuildStatus('docker/build', 'Build Docker image', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Build Docker image
          ----------------------------------------
          Image tag: ${VERSION}
          """
          echo "Build and push Docker image to internal Docker registry ${DOCKER_REGISTRY}"
          // fetch Nuxeo Tomcat Server with Maven
          sh "mvn ${MAVEN_ARGS} -T4C -f docker/pom.xml process-resources"
          retry(2) {
            sh 'skaffold build -f docker/skaffold.yaml'
          }
        }
      }
      post {
        success {
          setGitHubBuildStatus('docker/build', 'Build Docker image', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('docker/build', 'Build Docker image', 'FAILURE')
        }
      }
    }

    stage('Test Docker image') {
      steps {
        setGitHubBuildStatus('docker/test', 'Test Docker image', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Test Docker image
          ----------------------------------------
          """
          script {
            image = "${DOCKER_REGISTRY}/${dockerNamespace}/${NUXEO_IMAGE_NAME}:${VERSION}"
            echo "Test ${image}"
            dockerPull(image)
            echo 'Run image as root (0)'
            dockerRun(image, 'nuxeoctl start')
            echo 'Run image as an arbitrary user (800)'
            dockerRun(image, 'nuxeoctl start', '800')
          }
        }
      }
      post {
        success {
          setGitHubBuildStatus('docker/test', 'Test Docker image', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('docker/test', 'Test Docker image', 'FAILURE')
        }
      }
    }

    stage('Git tag and push') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        container('maven') {
          echo """
          ----------------------------------------
          Git tag and push
          ----------------------------------------
          """
          sh """
            #!/usr/bin/env bash -xe
            # create the Git credentials
            jx step git credentials
            git config credential.helper store

            # Git tag
            git tag -a v${VERSION} -m "Release ${VERSION}"
            git push origin v${VERSION}
          """
        }
      }
    }

    stage('Deploy Maven artifacts') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        setGitHubBuildStatus('maven/deploy', 'Deploy Maven artifacts', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Deploy Maven artifacts
          ----------------------------------------"""
          sh """
            mvn ${MAVEN_ARGS} -Pdistrib -DskipTests deploy
            mvn ${MAVEN_ARGS} -f parent/pom.xml deploy
          """
        }
      }
      post {
        success {
          setGitHubBuildStatus('maven/deploy', 'Deploy Maven artifacts', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('maven/deploy', 'Deploy Maven artifacts', 'FAILURE')
        }
      }
    }

    stage('Deploy Nuxeo Packages') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        setGitHubBuildStatus('packages/deploy', 'Deploy Nuxeo Packages', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Upload Nuxeo Packages to ${CONNECT_PREPROD_URL}
          ----------------------------------------"""
          withCredentials([usernameColonPassword(credentialsId: 'connect-preprod', variable: 'CONNECT_PASS')]) {
            sh """
              PACKAGES_TO_UPLOAD="packages/nuxeo-*-package/target/nuxeo-*-package-*.zip"
              for file in \$PACKAGES_TO_UPLOAD ; do
                curl --fail -i -u "$CONNECT_PASS" -F package=@\$(ls \$file) "$CONNECT_PREPROD_URL"/site/marketplace/upload?batch=true ;
              done
            """
          }
        }
      }
      post {
        success {
          setGitHubBuildStatus('packages/deploy', 'Deploy Nuxeo Packages', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('packages/deploy', 'Deploy Nuxeo Packages', 'FAILURE')
        }
      }
    }

    stage('Deploy Docker image') {
      when {
        allOf {
          not {
            branch 'PR-*'
          }
          not {
            environment name: 'DRY_RUN', value: 'true'
          }
        }
      }
      steps {
        setGitHubBuildStatus('docker/deploy', 'Deploy Docker image', 'PENDING')
        container('maven') {
          echo """
          ----------------------------------------
          Deploy Docker image
          ----------------------------------------
          Image tag: ${VERSION}
          """
          echo "Push Docker image to Docker registry ${PUBLIC_DOCKER_REGISTRY}"
          dockerDeploy("${PUBLIC_DOCKER_REGISTRY}", "${NUXEO_IMAGE_NAME}")
          echo "Push Docker image to Docker registry ${PRIVATE_DOCKER_REGISTRY}"
          dockerDeploy("${PRIVATE_DOCKER_REGISTRY}", "${NUXEO_IMAGE_NAME}")
        }
      }
      post {
        success {
          setGitHubBuildStatus('docker/deploy', 'Deploy Docker image', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('docker/deploy', 'Deploy Docker image', 'FAILURE')
        }
      }
    }

    stage('Deploy Server Preview') {
      when {
        not {
          branch 'PR-*'
        }
      }
      steps {
        setGitHubBuildStatus('server/preview', 'Deploy server preview', 'PENDING')
        container('maven') {
          script {
            echo """
            ----------------------------------------
            Deploy Preview environment
            ----------------------------------------"""
            boolean nsExists = sh(returnStatus: true, script: "kubectl get namespace ${PREVIEW_NAMESPACE}") == 0
            if (!nsExists) {
              echo 'Create preview namespace'
              sh "kubectl create namespace ${PREVIEW_NAMESPACE}"

              echo 'Deploy TLS secret replicator to preview namespace'
              sh """
                kubectl apply -f ci/helm/templates/empty-tls-secret-for-kubernetes-replicator.yaml \
                  --namespace=${PREVIEW_NAMESPACE}
              """

              echo 'Copy image pull secret to preview namespace'
              sh "kubectl --namespace=platform get secret kubernetes-docker-cfg -ojsonpath='{.data.\\.dockerconfigjson}' | base64 --decode > /tmp/config.json"
              sh """kubectl create secret generic kubernetes-docker-cfg \
                  --namespace=${PREVIEW_NAMESPACE} \
                  --from-file=.dockerconfigjson=/tmp/config.json \
                  --type=kubernetes.io/dockerconfigjson --dry-run -o yaml | kubectl apply -f -"""
            }

            echo 'Add chart repositories'
            helmAddBitnamiRepository()
            helmAddElasticRepository()
            helmAddNuxeoRepository()

            echo 'Substitute environment variables in chart values'
            helmGenerateValues("${HELM_VALUES_DIR}", true, 'preview')

            echo 'Upgrade external service releases'
            helmUpgradeMongoDB("${PREVIEW_NAMESPACE}")
            helmUpgradeElasticsearch("${PREVIEW_NAMESPACE}")

            if (nsExists) {
              // scale down nuxeo preview deployment, otherwise K8s won't be able to mount the binaries volume for the pods
              // TODO: rely on a statefulset in the nuxeo Helm chart, as in mongodb
              echo 'Scale down nuxeo preview deployment before release upgrade'
              sh """
                kubectl scale deployment ${PREVIEW_HELM_RELEASE} \
                  --namespace=${PREVIEW_NAMESPACE} \
                  --replicas=0
              """
            }

            echo 'Upgrade nuxeo preview release'
            sh """
              helm3 template ${NUXEO_CHART_REPOSITORY_NAME}/${NUXEO_CHART_NAME} \
                --values=${HELM_VALUES_DIR}/values-nuxeo.yaml~gen \
                --output-dir=target
            """
            helmUpgrade(
              "${PREVIEW_HELM_RELEASE}",
              "${NUXEO_CHART_REPOSITORY_NAME}",
              "${NUXEO_CHART_NAME}",
              "${NUXEO_CHART_VERSION}",
              "${PREVIEW_NAMESPACE}",
              "${HELM_VALUES_DIR}/values-nuxeo.yaml~gen"
            )
            rolloutStatus('deployment', "${PREVIEW_HELM_RELEASE}", "${TEST_LONG_ROLLOUT_STATUS_TIMEOUT}", "${PREVIEW_NAMESPACE}")

            host = sh(returnStdout: true, script: """
              kubectl get ingress ${PREVIEW_HELM_RELEASE} \
                --namespace=${PREVIEW_NAMESPACE} \
                -ojsonpath='{.spec.rules[*].host}'
            """)
            echo """
              -----------------------------------------------
              Preview available at: https://${host}
              -----------------------------------------------"""
          }
        }
      }
      post {
        always {
          archiveArtifacts allowEmptyArchive: true, artifacts: '**/target/**/*.yaml'
        }
        success {
          setGitHubBuildStatus('server/preview', 'Deploy server preview', 'SUCCESS')
        }
        unsuccessful {
          setGitHubBuildStatus('server/preview', 'Deploy server preview', 'FAILURE')
        }
      }
    }
  }

  post {
    always {
      script {
        if (!isPullRequest()) {
          // update JIRA issue
          step([$class: 'JiraIssueUpdater', issueSelector: [$class: 'DefaultIssueSelector'], scm: scm])
        }
      }
    }
    success {
      script {
        if (!isPullRequest() && env.DRY_RUN != 'true') {
          currentBuild.description = "Build ${VERSION}"
          if (!hudson.model.Result.SUCCESS.toString().equals(currentBuild.getPreviousBuild()?.getResult())) {
            slackSend(channel: "${SLACK_CHANNEL}", color: 'good', message: "Successfully built nuxeo/nuxeo ${BRANCH_NAME} #${BUILD_NUMBER}: ${BUILD_URL}")
          }
        }
      }
    }
    unsuccessful {
      script {
        if (!isPullRequest() && env.DRY_RUN != 'true') {
          slackSend(channel: "${SLACK_CHANNEL}", color: 'danger', message: "Failed to build nuxeo/nuxeo ${BRANCH_NAME} #${BUILD_NUMBER}: ${BUILD_URL}")
        }
      }
    }
  }
}
