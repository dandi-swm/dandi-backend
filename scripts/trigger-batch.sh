#!/bin/sh
# Jenkins 배치 잡이 내부 배치 API를 호출한다. 환경변수 TARGET_URL, BATCH_SECRET이 필요하다.
set -eu

echo "Triggering batch: $TARGET_URL"

# -f: HTTP 4xx, 5xx면 에러로 끝나 잡이 실패한다
# -sS: 진행바를 숨기고 에러만 출력한다
curl -f -sS -X POST "$TARGET_URL" \
    -H "X-Internal-Batch-Key: $BATCH_SECRET" \
    --max-time 150 --retry 2 --retry-delay 30 --retry-all-errors

echo "Batch triggered successfully."
