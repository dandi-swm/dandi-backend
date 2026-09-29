#!/bin/bash
set -euo pipefail

# 개발용 더미 데이터 시드.
#
# 사용법:
#   ./scripts/seed-dev.sh              # .env.local의 DB에 적재
#   DRY_RUN=1 ./scripts/seed-dev.sh    # 실행할 파일 목록만 출력
#   ENV_FILE=.env.dev ./scripts/seed-dev.sh   # 다른 환경 파일 사용
#
# 스키마가 이미 있어야 한다 (Flyway 마이그레이션이 끝난 뒤 실행할 것).
# flyway_schema_history를 제외한 모든 테이블을 비우고 다시 채운다.
#
# ---------------------------------------------------------------------------
# 스키마 버전별 시드 (오버레이 방식)
#
# 대상 DB의 Flyway 버전을 읽어 scripts/seed/ 아래에서 적용할 파일을 고른다.
# 개발 서버(V0.20)와 로컬(V0.22)처럼 스키마가 다른 환경에 같은 명령으로 적재하기 위함이다.
#
#   scripts/seed/common/   버전과 무관한 파일. 항상 실행된다
#   scripts/seed/v0.20/    그 버전에서 처음 필요해진 파일
#   scripts/seed/v0.21/    그 버전에서 바뀐 파일만. 같은 이름이면 아래 버전을 덮어쓴다
#   scripts/seed/v0.22/
#
# 현재 스키마 버전 이하의 버전 디렉터리를 낮은 순서대로 겹쳐 쌓고,
# 파일 이름이 같으면 더 높은 버전의 것이 이긴다.
#
#   V0.20 DB → common + v0.20
#   V0.22 DB → common + v0.20 + v0.21(10-users 덮어씀) + v0.22(30-cat 덮어씀, 55-... 추가)
#
# 실행 순서는 디렉터리와 무관하게 파일 이름의 숫자 접두사를 따른다(FK 순서 때문).
#
# 새 마이그레이션이 NOT NULL 칼럼을 추가하면, 기존 파일을 고치지 말고
# 새 버전 디렉터리에 바뀐 파일만 추가할 것. 그래야 낮은 버전 환경이 계속 돌아간다.
# ---------------------------------------------------------------------------

CONTAINER="${MYSQL_CONTAINER:-nyummy-mysql}"
DRY_RUN="${DRY_RUN:-0}"

cd "$(dirname "$0")/.."

SEED_DIR="scripts/seed"
ENV_FILE="${ENV_FILE:-.env.local}"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "[seed] $ENV_FILE 를 찾을 수 없습니다. 프로젝트 루트에서 실행했는지 확인하세요." >&2
    exit 1
fi

set -a
source "$ENV_FILE"
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

# mysql 클라이언트는 컨테이너 안의 것을 쓴다.
#
# 조회용(mysql_query)은 -i를 쓰지 않는다. docker exec -i는 표준 입력을 EOF까지 붙잡으므로,
# 아래 확인 프롬프트(read)가 읽을 입력까지 같이 삼켜버린다.
mysql_query() {
    docker exec "$CONTAINER" \
        mysql --default-character-set=utf8mb4 \
        -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB" "$@"
}

# 적재용. 파이프로 들어온 SQL을 그대로 흘려보내야 하므로 -i가 필요하다.
mysql_load() {
    docker exec -i "$CONTAINER" \
        mysql --default-character-set=utf8mb4 \
        -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB"
}

# ---------------------------------------------------------------------------
# 1. 대상 DB의 현재 Flyway 버전 확인
# ---------------------------------------------------------------------------
# version 칼럼은 VARCHAR라 MAX()를 쓰면 '0.9' > '0.22'가 되어버린다.
# 적용 순서(installed_rank)의 마지막 성공 건을 현재 버전으로 본다.
SCHEMA_VERSION=$(
    mysql_query -N -B -e "
        SELECT version FROM flyway_schema_history
        WHERE success = 1 AND version IS NOT NULL
        ORDER BY installed_rank DESC LIMIT 1
    " 2>/dev/null
) || {
    echo "[seed] flyway_schema_history를 읽을 수 없습니다. 마이그레이션이 끝났는지 확인하세요." >&2
    exit 1
}

if [[ -z "$SCHEMA_VERSION" ]]; then
    echo "[seed] 적용된 마이그레이션이 없습니다. 앱을 한 번 띄워 스키마를 만든 뒤 실행하세요." >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 2. 오버레이 해석
# ---------------------------------------------------------------------------
# $1 <= $2 인지 버전 비교 (sort -V는 0.9 < 0.20 을 올바르게 처리한다)
version_le() {
    [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$1" ]]
}

# "파일이름<TAB>경로" 목록을 만든다. 같은 파일 이름이 여러 번 들어오면 나중 것이 이긴다.
ENTRIES=()

for path in "$SEED_DIR"/common/*.sql; do
    ENTRIES+=("$(basename "$path")	$path")
done

APPLIED_LAYERS=()
for dir in $(find "$SEED_DIR" -maxdepth 1 -type d -name 'v*' | sort -V); do
    layer_version="$(basename "$dir")"
    layer_version="${layer_version#v}"

    version_le "$layer_version" "$SCHEMA_VERSION" || continue

    APPLIED_LAYERS+=("$(basename "$dir")")
    for path in "$dir"/*.sql; do
        ENTRIES+=("$(basename "$path")	$path")
    done
done

if [[ ${#APPLIED_LAYERS[@]} -eq 0 ]]; then
    echo "[seed] V$SCHEMA_VERSION 스키마에 맞는 버전 디렉터리가 없습니다." >&2
    echo "[seed] $SEED_DIR 아래 가장 낮은 버전이 지원 하한입니다. 마이그레이션을 먼저 올리세요." >&2
    exit 1
fi

# 같은 파일 이름은 마지막(가장 높은 버전) 것만 남기고, 이름순(숫자 접두사)으로 정렬한다.
FILES=()
while IFS=$'\t' read -r _ path; do
    FILES+=("$path")
done < <(
    printf '%s\n' "${ENTRIES[@]}" |
        awk -F'\t' '{ picked[$1] = $2 } END { for (name in picked) print name "\t" picked[name] }' |
        sort
)

echo "[seed] 대상 DB: $MYSQL_DB (스키마 V$SCHEMA_VERSION)"
echo "[seed] 오버레이: common → ${APPLIED_LAYERS[*]}"
echo "[seed] 실행할 파일:"
for f in "${FILES[@]}"; do
    echo "         - ${f#"$SEED_DIR"/}"
done

if [[ "$DRY_RUN" == "1" ]]; then
    echo "[seed] DRY_RUN=1 이므로 실행하지 않고 종료합니다."
    exit 0
fi

# ---------------------------------------------------------------------------
# 3. 적재
# ---------------------------------------------------------------------------
echo "[seed] '$MYSQL_DB'에 더미 데이터를 넣습니다. 기존 데이터는 모두 삭제됩니다."
read -r -p "[seed] 계속할까요? (y/N) " answer
if [[ "$answer" != "y" && "$answer" != "Y" ]]; then
    echo "[seed] 취소했습니다."
    exit 0
fi

# 세션 설정(SET NAMES / time_zone)이 이후 파일에도 적용되도록 한 번의 연결로 흘려보낸다.
cat "${FILES[@]}" | mysql_load

echo "[seed] 완료. 로그인 계정은 dev1@dandi.com ~ dev5@dandi.com / 비밀번호는 모두 Password123! 입니다."
echo "[seed] dev4@dandi.com만 is_temp_password=1 (임시 비밀번호 상태)입니다."
echo "[seed] 이메일 인증 코드(SIGNUP)는 verify1@dandi.com ~ verify5@dandi.com / 코드는 각각 111111 ~ 555555 입니다."
echo "[seed] 비밀번호 재설정 코드(RESET_PASSWORD)는 dev1@dandi.com / 코드는 666666 입니다."
echo "[seed] 아이콘 캐시(@Cacheable(\"icons\"))가 남아 있을 수 있으니 앱이 떠 있었다면 재시작하세요."

if version_le "0.22" "$SCHEMA_VERSION"; then
    echo "[seed] 고양이 체형은 dev1(감소), dev2(증가), dev3(하한 유지), dev4(주기 미도래), dev5(감소)로 깔려 있습니다."
fi
