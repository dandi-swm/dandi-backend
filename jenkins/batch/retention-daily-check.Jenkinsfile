// 매일 20:00 저녁 리텐션 통합 푸시를 보내는 배치 잡
// Jenkins에 Pipeline script from SCM으로 등록한다. triggers와 options는 잡을 한 번 돌려야 적용된다.
pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        disableResume()
        buildDiscarder(logRotator(daysToKeepStr: '30'))
        timeout(time: 5, unit: 'MINUTES')
    }

    triggers {
        cron("""TZ=Asia/Seoul
0 20 * * *""")
    }

    environment {
        BATCH_SECRET = credentials('NYUMMY_BATCH_SECRET')
        DISCORD_WEBHOOK_URL = credentials('DISCORD_WEBHOOK_URL')
        TARGET_URL = 'https://api.nyummy.co.kr/api/v1/internal/batch/retentions/daily-check'
        BATCH_DESCRIPTION = '데일리 리텐션 푸시'
    }

    stages {
        stage('Trigger Batch') {
            steps {
                sh 'scripts/trigger-batch.sh'
            }
        }
    }

    post {
        failure {
            sh 'scripts/notify-batch-failure.sh'
        }
    }
}
