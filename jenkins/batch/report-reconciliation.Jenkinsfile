// 매일 05:00 리포트·구매·환불·코인 내역·생성 작업이 서로 맞는지 대조하는 배치 잡
// 불일치를 찾으면 잡이 실패해 디스코드로 알린다. 고치는 일은 응답의 리포트 id를 보고 사람이 한다.
// Jenkins에 Pipeline script from SCM으로 등록한다. triggers와 options는 잡을 한 번 돌려야 적용된다.
pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        disableResume()
        buildDiscarder(logRotator(daysToKeepStr: '30'))
        timeout(time: 10, unit: 'MINUTES')
    }

    triggers {
        cron("""TZ=Asia/Seoul
0 5 * * *""")
    }

    environment {
        BATCH_SECRET = credentials('NYUMMY_BATCH_SECRET')
        DISCORD_WEBHOOK_URL = credentials('DISCORD_WEBHOOK_URL')
        TARGET_URL = 'https://api.nyummy.co.kr/api/v1/internal/batch/reports/reconciliation'
        BATCH_DESCRIPTION = '리포트 정합성 대조(불일치를 찾았거나 호출에 실패)'
    }

    stages {
        stage('Trigger Batch') {
            steps {
                sh 'scripts/trigger-report-reconciliation.sh'
            }
        }
    }

    post {
        failure {
            sh 'scripts/notify-batch-failure.sh'
        }
    }
}
