#!/bin/sh
# Jenkins 배치 잡이 리포트 대조 API를 호출하고, 불일치가 있으면 잡을 실패로 끝내 알림을 보낸다.
# 환경변수 TARGET_URL, BATCH_SECRET이 필요하다. 호출 방식은 trigger-batch.sh와 같다.
set -eu

echo "Triggering batch: $TARGET_URL"

RESPONSE=$(curl -f -sS -X POST "$TARGET_URL" \
    -H "X-Internal-Batch-Key: $BATCH_SECRET" \
    --max-time 150 --retry 2 --retry-delay 30 --retry-all-errors)

echo "$RESPONSE"

# 응답은 공백 없는 JSON이다. mismatchCount가 0일 때만 성공으로 본다.
if echo "$RESPONSE" | grep -Eq '"mismatchCount":0[,}]'; then
    echo "리포트 불일치가 없습니다."
else
    echo "리포트 불일치를 찾았습니다. 응답의 mismatches에서 종류와 리포트 id를 확인하세요." >&2
    exit 1
fi
