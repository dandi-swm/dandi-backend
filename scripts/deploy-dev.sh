#!/usr/bin/env bash
set -euo pipefail

IMAGE_URI="${1:?Image URI is required}"

AWS_REGION="ap-northeast-2"
APP_DIR="/opt/nyummy/app"

# 임의의 이미지 주소나 셸 인자가 들어오지 않도록 검증
if [[ ! "$IMAGE_URI" =~ ^[0-9]{12}\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/nyummy-backend:[a-f0-9]{12}-[0-9]+$ ]]; then
    echo "Invalid image URI"
    exit 1
fi

cd "$APP_DIR"

REGISTRY="${IMAGE_URI%%/*}"

echo "Deploying: $IMAGE_URI"

# ECR 로그인
aws ecr get-login-password \
    --region "$AWS_REGION" \
    | docker login \
        --username AWS \
        --password-stdin "$REGISTRY"

# 새로운 이미지 다운로드
docker pull "$IMAGE_URI"

# 기존 MySQL, Valkey는 유지하고 app만 교체
ECR_IMAGE_URI="$IMAGE_URI" \
docker compose \
    --env-file .env.dev \
    -f docker-compose.yml \
    -f docker-compose.dev.yml \
    up -d --no-deps --force-recreate app

echo "Container status:"
docker compose \
    --env-file .env.dev \
    -f docker-compose.yml \
    -f docker-compose.dev.yml \
    ps

echo "Deployment command completed"