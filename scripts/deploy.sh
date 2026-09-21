#!/bin/bash
set -euo pipefail

# 개발 EC2 배포. GitHub Actions가 이 파일을 서버에 복사한 뒤 실행한다.
# 환경 파일은 서버에 미리 두어야 한다: ~/nyummy/.env.dev (.env.dev.example 참고)

cd ~/nyummy

ENV_FILE="${ENV_FILE:-.env.dev}"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "[deploy] $ENV_FILE 를 찾을 수 없습니다. .env.dev.example을 복사해 채우세요." >&2
    exit 1
fi

set -a
source "$ENV_FILE"
set +a

aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY"
docker compose --env-file "$ENV_FILE" pull
docker compose --env-file "$ENV_FILE" up -d
docker system prune -f
