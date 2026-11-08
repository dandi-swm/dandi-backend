package com.dandi.nyummy.worker.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Worker 설정. Worker는 같은 이미지를 별도 ECS 서비스로 띄우고, 그 서비스에서만 enabled를 켠다.
 *
 * 작업 종류(영양 분석 등)마다 설정을 따로 두어, 종류별로 다른 ECS 서비스에서 켜고 스레드 수를 따로 정할 수 있다.
 *
 * @property enabled Worker 빈(스케줄링·종류별 polling·실행기)을 만든다. API 서비스는 기본값(false)이라 Worker 설정이 필요 없다
 * @property shutdownTimeout 종료할 때 처리 중인 작업이 끝나기를 기다리는 최대 시간. 정상 처리 시간보다 길어야 하고,
 *   나머지 종료 시간을 더해도 ECS stopTimeout보다 짧아야 기다리는 도중에 강제로 끝나지 않는다
 * @property analysis 영양 분석 작업(meal_analysis_jobs) 설정
 */
@ConfigurationProperties(prefix = "app.worker")
data class WorkerProperties(
    val enabled: Boolean = false,
    val shutdownTimeout: Duration = Duration.ofSeconds(40),
    val analysis: WorkerJobProperties = WorkerJobProperties(),
) {
    /**
     * 켜진 작업 종류의 스레드 수 합. 모든 스레드가 같은 Gemini 연결 풀을 쓰므로 연결 수를 넘으면 안 된다.
     */
    fun calculateEnabledMaxThreads(): Int = listOf(analysis).filter { it.enabled }.sumOf { it.maxThreads }
}

/**
 * 작업 종류 하나의 Worker 설정.
 *
 * @property enabled 이 종류의 polling·실행기를 만든다. [WorkerProperties.enabled]가 켜져 있을 때만 본다
 * @property maxThreads 작업 한 건을 처리하는 실행기의 스레드 수(= 이 종류의 동시 처리 수)
 * @property leaseTimeToLive 작업을 가져간 Worker가 그 작업을 쥐고 있는 시간. 지나면 다른 Worker가 다시 가져갈 수 있다.
 *   한 건 처리가 끝나는 시간보다 길어야 같은 작업을 두 Worker가 함께 처리하지 않는다
 * @property maxLeaseCount 한 작업을 가져갈 수 있는 최대 횟수(= 최대 시도 수). 일시적인 실패도 이 횟수를 채우면 FAILED로 끝낸다
 * @property leaseLimit polling 한 번에 가져갈 최대 작업 수. 빈 자리가 더 많아도 이만큼만 가져간다
 * @property pollingDelay polling 한 번이 끝난 뒤 다음 polling까지 쉬는 시간. Worker 하나가 받아들이는 속도의 상한은
 *   약 leaseLimit ÷ (pollingDelay + polling 한 번 시간)이고, 줄이면 그만큼 DB 조회가 잦아진다(일이 없어도).
 *   `@Scheduled`가 같은 키(`app.worker.<종류>.polling-delay`)를 직접 읽는다
 */
data class WorkerJobProperties(
    val enabled: Boolean = true,
    val maxThreads: Int = 50,
    val leaseTimeToLive: Duration = Duration.ofSeconds(60),
    val maxLeaseCount: Int = 2,
    val leaseLimit: Int = 10,
    val pollingDelay: Duration = Duration.ofSeconds(1),
)
