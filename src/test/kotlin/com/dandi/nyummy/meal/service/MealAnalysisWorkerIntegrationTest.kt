package com.dandi.nyummy.meal.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.infra.ai.AiConfig
import com.dandi.nyummy.infra.ai.AiProperties
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisResult
import com.dandi.nyummy.meal.config.MealAnalysisProperties
import com.dandi.nyummy.meal.config.MealAsyncConfig
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisQueue
import com.dandi.nyummy.meal.entity.MealOutbox
import com.dandi.nyummy.meal.enum.MealAnalysisQueueStatus
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.queue.DbMealAnalysisConsumer
import com.dandi.nyummy.meal.repository.MealAnalysisQueueRepository
import com.dandi.nyummy.meal.repository.MealOutboxRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import com.sun.net.httpserver.HttpServer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MealAnalysisWorkerIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")
    }

    @Autowired
    private lateinit var meals: MealRepository

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var queue: MealAnalysisQueueRepository

    @Autowired
    private lateinit var outboxes: MealOutboxRepository

    @Autowired
    private lateinit var consumer: DbMealAnalysisConsumer

    @Autowired
    private lateinit var handler: AnalysisHandler

    @Autowired
    private lateinit var transaction: TransactionTemplate

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var aiProperties: AiProperties

    @Autowired
    @Qualifier("mealAnalysisExecutor")
    private lateinit var executor: ThreadPoolTaskExecutor

    @MockitoBean
    private lateinit var ai: NutritionAnalysisClient

    @MockitoSpyBean
    private lateinit var analysisService: AnalysisService

    private val analysisServiceTarget: AnalysisService
        get() = AopTestUtils.getUltimateTargetObject(analysisService)

    private val result = NutritionAnalysisResult("샐러드", Nutrition(200, 20, 10, 9), "맛있게 먹었다냥", 1)
    private var userId = 0L

    @BeforeEach
    fun setUp() {
        awaitIdle()
        queue.deleteAllInBatch()
        outboxes.deleteAllInBatch()
        meals.deleteAllInBatch()
        users.deleteAllInBatch()
        jdbc.update("INSERT IGNORE INTO icon (id, name, image_url) VALUES (1, '음식', 'test')")
        userId = users.save(User(AuthProvider.EMAIL, email = "worker@test.com")).id
        `when`(ai.analyzeNutrition(anyString())).thenReturn(result)
    }

    @AfterEach
    fun awaitIdle() {
        await().atMost(Duration.ofSeconds(10)).until {
            executor.activeCount == 0 && executor.threadPoolExecutor.queue.isEmpty()
        }
    }

    @Test
    fun `Worker 스레드의 AI 호출에는 트랜잭션이 없고 성공 결과와 큐 상태는 함께 저장된다`() {
        val job = enqueue()
        val correctThread = AtomicBoolean(false)
        val noTransaction = AtomicBoolean(false)
        `when`(ai.analyzeNutrition(anyString())).thenAnswer {
            correctThread.set(Thread.currentThread().name.startsWith("meal-analysis-"))
            noTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive())
            assertEquals(MealStatus.ANALYZING, meals.findById(job.mealId).orElseThrow().status)
            assertEquals(MealAnalysisQueueStatus.PROCESSING, queue.findById(job.id).orElseThrow().status)
            result
        }

        consumer.poll()
        awaitStatus(job.id, MealAnalysisQueueStatus.DONE)

        val meal = meals.findById(job.mealId).orElseThrow()
        assertTrue(correctThread.get())
        assertTrue(noTransaction.get())
        assertEquals(MealStatus.COMPLETED, meal.status)
        assertEquals(result.nutrition.calory, meal.calory)
        assertEquals(result.name, meal.name)
        assertNotNull(queue.findById(job.id).orElseThrow().finishedAt)
    }

    @Test
    fun `실행 자리가 없으면 조회하지 않고 자리가 반환되면 나머지 작업을 가져온다`() {
        val pending = List(3) { enqueue() }
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        `when`(ai.analyzeNutrition(anyString())).thenAnswer {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            result
        }

        try {
            consumer.poll()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            clearInvocations(analysisServiceTarget)
            consumer.poll()
            verify(analysisServiceTarget, never()).startNutritionAnalyses(anyInt())
            assertEquals(MealAnalysisQueueStatus.READY, queue.findById(pending.last().id).orElseThrow().status)
            verify(ai, times(2)).analyzeNutrition(anyString())
        } finally {
            release.countDown()
        }

        awaitIdle()
        consumer.poll()
        awaitStatus(pending.last().id, MealAnalysisQueueStatus.DONE)
        verify(ai, times(3)).analyzeNutrition(anyString())
    }

    @Test
    fun `빈 큐와 확보한 자리보다 적은 작업을 처리한 뒤 실행 자리가 반환된다`() {
        consumer.poll()
        val first = enqueue()
        consumer.poll()
        awaitStatus(first.id, MealAnalysisQueueStatus.DONE)
        awaitIdle()
        val remaining = List(2) { enqueue() }
        consumer.poll()
        remaining.forEach { awaitStatus(it.id, MealAnalysisQueueStatus.DONE) }
    }

    @Test
    fun `선점 실패 후에도 실행 자리가 반환되고 다음 폴링에서 처리한다`() {
        val pending = List(2) { enqueue() }
        val failOnce = AtomicBoolean(true)
        doAnswer { invocation ->
            if (failOnce.getAndSet(false)) error("DB 조회 실패")
            invocation.callRealMethod()
        }.`when`(analysisServiceTarget).startNutritionAnalyses(anyInt())

        consumer.poll()
        assertTrue(queue.findAll().all { it.status == MealAnalysisQueueStatus.READY })
        consumer.poll()
        pending.forEach { awaitStatus(it.id, MealAnalysisQueueStatus.DONE) }
    }

    @Test
    fun `Executor 제출이 거부되면 실패 처리하고 실행 자리를 반환한다`() {
        val reject = AtomicBoolean(true)
        val controlled = DbMealAnalysisConsumer(
            analysisService,
            handler,
            TaskExecutor { task ->
                if (reject.get()) throw TaskRejectedException("테스트 제출 거부")
                task.run()
            },
            MealAnalysisProperties(),
        )
        val rejected = List(2) { enqueue() }

        controlled.poll()
        rejected.forEach {
            assertEquals(MealAnalysisQueueStatus.FAILED, queue.findById(it.id).orElseThrow().status)
            assertEquals(MealStatus.FAILED, meals.findById(it.mealId).orElseThrow().status)
        }
        verify(ai, never()).analyzeNutrition(anyString())

        reject.set(false)
        val next = List(2) { enqueue() }
        controlled.poll()
        next.forEach { assertEquals(MealAnalysisQueueStatus.DONE, queue.findById(it.id).orElseThrow().status) }
    }

    @Test
    fun `분석 오류는 실패로 종료하고 다음 폴링에서도 재분석하지 않는다`() {
        val job = enqueue()
        `when`(ai.analyzeNutrition(anyString())).thenThrow(IllegalStateException("AI 오류"))

        consumer.poll()
        awaitStatus(job.id, MealAnalysisQueueStatus.FAILED)
        awaitIdle()
        consumer.poll()

        assertEquals(MealStatus.FAILED, meals.findById(job.mealId).orElseThrow().status)
        verify(ai, times(1)).analyzeNutrition(anyString())
    }

    @Test
    fun `HTTP 응답 본문 수신도 timeout으로 중단되고 작업을 실패로 저장한다`() {
        assertEquals(Duration.ofSeconds(30), aiProperties.readTimeout)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/analysis") { exchange ->
            requests.incrementAndGet()
            try {
                exchange.requestBody.readAllBytes()
                exchange.sendResponseHeaders(200, 10)
                exchange.responseBody.write('x'.code)
                exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            // 운영과 동일한 HTTP 팩토리를 사용하되 테스트 대기 시간만 단축한다.
            val client = AiConfig().aiRestClient(
                AiProperties(
                    "test",
                    "test",
                    "http://127.0.0.1:${server.address.port}",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                ),
            )
            `when`(ai.analyzeNutrition(anyString())).thenAnswer {
                client.post().uri("/analysis").body("test").retrieve().body(String::class.java)
                result
            }
            val job = enqueue()
            consumer.poll()
            awaitStatus(job.id, MealAnalysisQueueStatus.FAILED)
            awaitIdle()
            consumer.poll()

            assertEquals(MealStatus.FAILED, meals.findById(job.mealId).orElseThrow().status)
            assertEquals(1, requests.get())
            verify(ai, times(1)).analyzeNutrition(anyString())
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `결과 저장이 DB 제약으로 실패하면 식사와 큐 종료 변경이 모두 롤백된다`() {
        val job = enqueue()
        `when`(ai.analyzeNutrition(anyString())).thenReturn(result.copy(iconId = Long.MAX_VALUE))

        consumer.poll()
        awaitIdle()

        val meal = meals.findById(job.mealId).orElseThrow()
        assertEquals(MealStatus.ANALYZING, meal.status)
        assertNull(meal.calory)
        assertEquals(MealAnalysisQueueStatus.PROCESSING, queue.findById(job.id).orElseThrow().status)
        assertNull(queue.findById(job.id).orElseThrow().finishedAt)
        consumer.poll()
        verify(ai, times(1)).analyzeNutrition(anyString())
    }

    @Test
    fun `삭제된 식사는 선점해도 분석하지 않고 큐를 종료한다`() {
        val job = enqueue()
        transaction.executeWithoutResult {
            assertNotNull(meals.findByIdForUpdate(job.mealId)).updateDeletedAt(Instant.now())
        }

        consumer.poll()

        assertEquals(MealAnalysisQueueStatus.FAILED, queue.findById(job.id).orElseThrow().status)
        verify(ai, never()).analyzeNutrition(anyString())
    }

    @Test
    fun `분석 중 삭제된 식사에는 결과를 반영하지 않는다`() {
        val job = enqueue()
        val claimed = analysisService.startNutritionAnalyses(1).single()
        transaction.executeWithoutResult {
            assertNotNull(meals.findByIdForUpdate(job.mealId)).updateDeletedAt(Instant.now())
        }

        analysisService.completeNutritionAnalysis(claimed, result)

        assertNull(meals.findById(job.mealId).orElseThrow().calory)
        assertEquals(MealAnalysisQueueStatus.FAILED, queue.findById(job.id).orElseThrow().status)
    }

    @Test
    fun `이미 종료된 작업의 늦은 결과와 실패 처리는 현재 식사 상태를 덮어쓰지 않는다`() {
        val job = enqueue()
        val claimed = analysisService.startNutritionAnalyses(1).single()
        analysisService.failNutritionAnalysis(job.id)
        transaction.executeWithoutResult {
            assertNotNull(meals.findByIdForUpdate(job.mealId)).updateStatus(MealStatus.WAITING)
        }

        analysisService.completeNutritionAnalysis(claimed, result)
        analysisService.failNutritionAnalysis(job.id)

        assertEquals(MealStatus.WAITING, meals.findById(job.mealId).orElseThrow().status)
        assertNull(meals.findById(job.mealId).orElseThrow().calory)
    }

    @Test
    fun `분석 도중 수정한 식사 이름은 결과 저장 시 보존한다`() {
        val job = enqueue()
        val claimed = analysisService.startNutritionAnalyses(1).single()
        transaction.executeWithoutResult {
            assertNotNull(meals.findByIdForUpdate(job.mealId)).updateName("직접 입력한 이름")
        }

        analysisService.completeNutritionAnalysis(claimed, result)

        assertEquals("직접 입력한 이름", meals.findById(job.mealId).orElseThrow().name)
        assertEquals(MealStatus.COMPLETED, meals.findById(job.mealId).orElseThrow().status)
    }

    @Test
    fun `두 Consumer가 동시에 실행해도 같은 작업을 중복 분석하지 않는다`() {
        val pending = List(4) { enqueue() }
        // 각 ECS Task는 독립된 실행 자리와 Worker 풀을 가진다.
        val otherExecutor = MealAsyncConfig().mealAnalysisExecutor(MealAnalysisProperties()).apply { initialize() }
        val other = DbMealAnalysisConsumer(analysisService, handler, otherExecutor, MealAnalysisProperties())
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit {
                check(start.await(5, TimeUnit.SECONDS))
                consumer.poll()
            }
            val second = threads.submit {
                check(start.await(5, TimeUnit.SECONDS))
                other.poll()
            }
            start.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            pending.forEach { awaitStatus(it.id, MealAnalysisQueueStatus.DONE) }
            pending.forEach {
                verify(ai, times(1)).analyzeNutrition(meals.findById(it.mealId).orElseThrow().imageKey)
            }
        } finally {
            start.countDown()
            threads.shutdownNow()
            threads.awaitTermination(5, TimeUnit.SECONDS)
            otherExecutor.shutdown()
        }
    }

    private fun enqueue(): MealAnalysisQueue {
        val meal = meals.saveAndFlush(
            Meal(MealStatus.WAITING, "meals/$userId/${UUID.randomUUID()}", Instant.now(), userId),
        )
        val outbox = outboxes.save(MealOutbox(meal.id, Instant.now()))
        return queue.saveAndFlush(MealAnalysisQueue(outbox.id, meal.id, Instant.now()))
    }

    private fun awaitStatus(id: Long, status: MealAnalysisQueueStatus) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertEquals(status, queue.findById(id).orElseThrow().status)
        }
    }
}
