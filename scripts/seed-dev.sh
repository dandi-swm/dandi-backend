#!/bin/bash
set -euo pipefail

# 로컬 개발용 더미 데이터 시드.
#
# 사용법:
#   ./scripts/seed-dev.sh
#
# 스키마가 이미 있어야 한다 (앱을 한 번 띄워 Flyway 마이그레이션을 끝낸 뒤 실행할 것).
# seed-dev.sql이 flyway_schema_history를 제외한 모든 테이블을 비우고 다시 채운다.

CONTAINER="${MYSQL_CONTAINER:-nyummy-mysql}"

cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
    echo "[seed] .env를 찾을 수 없습니다. 프로젝트 루트에서 실행했는지 확인하세요." >&2
    exit 1
fi

set -a
source .env
set +a

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "[seed] MySQL 컨테이너 '$CONTAINER'가 실행 중이 아닙니다." >&2
    echo "[seed] 컨테이너 이름이 다르면 MYSQL_CONTAINER 환경변수로 지정하세요." >&2
    exit 1
fi

# 운영 DB를 가리키고 있으면 중단한다. TRUNCATE가 포함된 스크립트라 되돌릴 수 없다.
if [[ "${MYSQL_HOST:-localhost}" != "localhost" && "${MYSQL_HOST:-localhost}" != "127.0.0.1" ]]; then
    echo "[seed] MYSQL_HOST가 '$MYSQL_HOST' 입니다. 로컬 DB에서만 실행하세요." >&2
    exit 1
fi

echo "[seed] '$MYSQL_DB'에 더미 데이터를 넣습니다. 기존 데이터는 모두 삭제됩니다."
read -r -p "[seed] 계속할까요? (y/N) " answer
if [[ "$answer" != "y" && "$answer" != "Y" ]]; then
    echo "[seed] 취소했습니다."
    exit 0
fi

docker exec -i "$CONTAINER" \
    mysql --default-character-set=utf8mb4 \
    -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" \
    < scripts/seed-dev.sql

echo "[seed] 완료. 로그인 계정은 dev1@dandi.com ~ dev5@dandi.com / 비밀번호는 모두 Password123! 입니다."
echo "[seed] 이메일 인증 코드는 verify1@dandi.com ~ verify5@dandi.com / 코드는 각각 111111 ~ 555555 입니다."
