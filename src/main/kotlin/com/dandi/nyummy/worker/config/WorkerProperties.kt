package com.dandi.nyummy.worker.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Worker 설정. Worker는 같은 이미지를 별도 ECS 서비스로 띄우고, 그 서비스에서만 enabled를 켠다.
 *
 * @property enabled Worker 빈(polling·실행기)을 만든다. API 서비스는 기본값(false)이라 Worker 설정이 필요 없다
 * @property leaseTimeToLive 작업을 가져간 Worker가 그 작업을 쥐고 있는 시간. 지나면 다른 Worker가 다시 가져갈 수 있다.
 *   한 건 처리(S3 다운로드 + Gemini 호출 + 결과 저장)가 끝나는 시간보다 길어야 같은 작업을 두 Worker가 함께 처리하지 않는다
 * @property maxLeaseCount 한 작업을 가져갈 수 있는 최대 횟수(= 최대 시도 수). 일시적인 실패도 이 횟수를 채우면 FAILED로 끝낸다
 * @property leaseLimit polling 한 번에 가져갈 최대 작업 수. 빈 자리가 더 많아도 이만큼만 가져간다
 * @property shutdownTimeout 종료할 때 처리 중인 작업이 끝나기를 기다리는 최대 시간. 정상 처리 시간보다 길어야 하고,
 *   나머지 종료 시간을 더해도 ECS stopTimeout보다 짧아야 기다리는 도중에 강제로 끝나지 않는다
 */
@ConfigurationProperties(prefix = "app.worker")
data class WorkerProperties(
    val enabled: Boolean = false,
    val leaseTimeToLive: Duration = Duration.ofSeconds(60),
    val maxLeaseCount: Int = 2,
    val leaseLimit: Int = 10,
    val shutdownTimeout: Duration = Duration.ofSeconds(40),
)
