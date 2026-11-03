// 매일 23:40 상품의 최근 판매량을 갱신하는 배치 잡
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
40 23 * * *""")
    }

    environment {
        BATCH_SECRET = credentials('NYUMMY_BATCH_SECRET')
        DISCORD_WEBHOOK_URL = credentials('DISCORD_WEBHOOK_URL')
        TARGET_URL = 'https://api.nyummy.co.kr/api/v1/internal/batch/shop/sales-counts'
        BATCH_DESCRIPTION = '상품 판매량 갱신'
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
