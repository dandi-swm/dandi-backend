package com.dandi.nyummy.worker.config

import com.dandi.nyummy.infra.ai.AiProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerConfigTest {

    private val maxConnections = 50

    private val aiProperties = AiProperties(
        apiKey = "test-gemini-key",
        model = "gemini-test",
        recognitionModel = "gemini-recognition-test",
        servingNutritionModel = "gemini-serving-nutrition-test",
        catCommentModel = "gemini-cat-comment-test",
        baseUrl = "https://aiplatform.googleapis.com",
        connectTimeout = Duration.ofSeconds(5),
        readTimeout = Duration.ofSeconds(30),
        maxConnections = maxConnections,
        connectionRequestTimeout = Duration.ofSeconds(3),
    )

    private fun createWorkerProperties(analysis: WorkerJobProperties) =
        WorkerProperties(enabled = true, analysis = analysis)

    @Test
    fun `켜진 종류의 스레드 수 합이 Gemini 연결 수와 같으면 만들어진다`() {
        WorkerConfig(createWorkerProperties(WorkerJobProperties(maxThreads = maxConnections)), aiProperties)
    }

    @Test
    fun `켜진 종류의 스레드 수 합이 Gemini 연결 수보다 많으면 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            WorkerConfig(createWorkerProperties(WorkerJobProperties(maxThreads = maxConnections + 1)), aiProperties)
        }
    }

    @Test
    fun `꺼진 종류의 스레드 수는 합에 넣지 않는다`() {
        val disabledAnalysis = WorkerJobProperties(enabled = false, maxThreads = maxConnections + 1)
        val workerProperties = createWorkerProperties(disabledAnalysis)

        assertEquals(0, workerProperties.calculateEnabledMaxThreads())
        WorkerConfig(workerProperties, aiProperties)
    }

    @Test
    fun `분석과 리포트를 함께 켜면 두 스레드 수의 합을 연결 수와 비교한다`() {
        val analysis = WorkerJobProperties(maxThreads = 40)

        WorkerConfig(
            WorkerProperties(enabled = true, analysis = analysis, report = WorkerJobProperties(maxThreads = 10)),
            aiProperties,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkerConfig(
                WorkerProperties(enabled = true, analysis = analysis, report = WorkerJobProperties(maxThreads = 11)),
                aiProperties,
            )
        }
    }
}
