#!/bin/sh
# Jenkins 배치 잡이 실패하면 디스코드로 알린다. 환경변수 BATCH_DESCRIPTION, DISCORD_WEBHOOK_URL과 Jenkins의 빌드 변수가 필요하다.
set -u

echo "배치 실패: 디스코드로 에러 웹훅을 발송합니다."

PAYLOAD=$(cat <<JSON
{
  "username": "Jenkins Batch Bot",
  "embeds": [
    {
      "title": "🚨 [배치 실패] ${JOB_NAME} #${BUILD_NUMBER}",
      "description": "${BATCH_DESCRIPTION} 배치 호출에 실패했습니다.\n서버 상태 및 로그를 점검하세요.",
      "url": "${BUILD_URL}console",
      "color": 15158332,
      "fields": [
        {
          "name": "빌드 로그 확인",
          "value": "[Jenkins Console 바로가기](${BUILD_URL}console)",
          "inline": false
        }
      ],
      "footer": {
        "text": "Nyummy PROD Monitoring"
      }
    }
  ]
}
JSON
)

curl -sS -X POST "$DISCORD_WEBHOOK_URL" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD"
