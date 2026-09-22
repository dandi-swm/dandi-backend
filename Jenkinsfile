pipeline {
    agent any

    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timestamps()
    }

    triggers {
        pollSCM('H/5 * * * *')
    }

    environment {
        AWS_REGION = 'ap-northeast-2'
        ECR_REPOSITORY = 'nyummy-backend'
        DEV_INSTANCE_ID = 'i-09233fde1ceb5e562'
    }

    stages {

        stage('Checkout') {
            steps {
                git branch: 'main',
                    url: 'https://github.com/dandi-swm/dandi-backend.git'
            }
        }

        stage('Prepare Image Tag') {
            steps {
                script {
                    def gitSha = sh(
                        script: 'git rev-parse --short=12 HEAD',
                        returnStdout: true
                    ).trim()

                    def accountId = sh(
                        script: 'aws sts get-caller-identity --query Account --output text',
                        returnStdout: true
                    ).trim()

                    env.IMAGE_TAG = "${gitSha}-${env.BUILD_NUMBER}"

                    env.ECR_REGISTRY =
                        "${accountId}.dkr.ecr.${env.AWS_REGION}.amazonaws.com"

                    env.IMAGE_URI =
                        "${env.ECR_REGISTRY}/${env.ECR_REPOSITORY}:${env.IMAGE_TAG}"

                    echo "Image tag: ${env.IMAGE_TAG}"
                }
            }
        }

        stage('Gradle Test') {
            steps {
                sh '''#!/bin/bash
                    set -euo pipefail

                    mkdir -p "$HOME/.gradle"

                    # Docker 소켓에 접근할 수 있도록 그룹 ID 확인
                    DOCKER_GID=$(stat -c '%g' /var/run/docker.sock)

                    docker run --rm \
                      --network host \
                      --user "$(id -u):$(id -g)" \
                      --group-add "$DOCKER_GID" \
                      -v "$PWD:$PWD" \
                      -v "$HOME/.gradle:/gradle-cache" \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      -e GRADLE_USER_HOME=/gradle-cache \
                      -e SPRING_PROFILES_ACTIVE=test \
                      -e TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
                      -w "$PWD" \
                      eclipse-temurin:25-jdk \
                      sh ./gradlew test --no-daemon --max-workers=1
                '''
            }
        }

        stage('Docker Build') {
            steps {
                sh '''#!/bin/bash
                    set -euo pipefail

                    docker build \
                      -t "$IMAGE_URI" \
                      .
                '''
            }
        }

        stage('ECR Push') {
            steps {
                sh '''#!/bin/bash
                    set -euo pipefail

                    export DOCKER_CONFIG
                    DOCKER_CONFIG="$(mktemp -d)"

                    trap 'rm -rf "$DOCKER_CONFIG"' EXIT

                    aws ecr get-login-password \
                      --region "$AWS_REGION" \
                    | docker login \
                      --username AWS \
                      --password-stdin "$ECR_REGISTRY"

                    docker push "$IMAGE_URI"
                '''
            }
        }

        stage('Deploy Dev') {
            steps {
                sh '''#!/bin/bash
                    set -euo pipefail

                    : "${IMAGE_URI:?IMAGE_URI is required}"
                    : "${DEV_INSTANCE_ID:?DEV_INSTANCE_ID is required}"

                    COMMAND_ID=$(
                        aws ssm send-command \
                            --region ap-northeast-2 \
                            --instance-ids "$DEV_INSTANCE_ID" \
                            --document-name AWS-RunShellScript \
                            --parameters "commands=[\\"/bin/bash /opt/nyummy/deploy-dev.sh $IMAGE_URI\\"]" \
                            --query 'Command.CommandId' \
                            --output text
                    )

                    echo "SSM Command ID: $COMMAND_ID"

                    # 실제 실행 결과를 확인
                    for i in $(seq 1 60); do
                        STATUS=$(
                            aws ssm get-command-invocation \
                                --region ap-northeast-2 \
                                --command-id "$COMMAND_ID" \
                                --instance-id "$DEV_INSTANCE_ID" \
                                --query Status \
                                --output text 2>/dev/null
                        ) || STATUS="Pending"

                        case "$STATUS" in
                            Success)
                                echo "SSM deployment command succeeded"
                                exit 0
                                ;;

                            Failed|Cancelled|TimedOut)
                                echo "SSM deployment failed: $STATUS"

                                aws ssm get-command-invocation \
                                    --region ap-northeast-2 \
                                    --command-id "$COMMAND_ID" \
                                    --instance-id "$DEV_INSTANCE_ID" \
                                    --query '[StandardOutputContent,StandardErrorContent]' \
                                    --output text || true

                                exit 1
                                ;;
                        esac

                        sleep 5
                    done

                    echo "SSM deployment result timeout"
                    exit 1
                '''
            }
        }
    }

    post {
        success {
            echo "CI SUCCESS: ${env.IMAGE_URI}"
        }

        failure {
            echo 'CI FAILED: Console Output을 확인하세요.'
        }
    }
}