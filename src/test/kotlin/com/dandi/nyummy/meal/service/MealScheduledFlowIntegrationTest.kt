package com.dandi.nyummy.meal.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisResult
import com.dandi.nyummy.infra.aws.s3.S3Service
import com.dandi.nyummy.meal.config.MealAnalysisProperties
import com.dandi.nyummy.meal.config.MealOutboxProperties
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealOutbox
import com.dandi.nyummy.meal.enum.MealAnalysisQueueStatus
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.queue.DbMealAnalysisPublisher
import com.dandi.nyummy.meal.queue.MealAnalysisMessage
import com.dandi.nyummy.meal.repository.MealAnalysisQueueRepository
import com.dandi.nyummy.meal.repository.MealOutboxRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.jwt.TokenService
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest(
    properties = [
        "app.meal.outbox.recovery-enabled=true",
        "app.meal.analysis.polling-enabled=true",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MealScheduledFlowIntegrationTest {
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
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var meals: MealRepository

    @Autowired
    private lateinit var users: UserRepository

    @Autowired
    private lateinit var outboxes: MealOutboxRepository

    @Autowired
    private lateinit var queue: MealAnalysisQueueRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transaction: TransactionTemplate

    @Autowired
    private lateinit var analysisProperties: MealAnalysisProperties

    @Autowired
    private lateinit var outboxProperties: MealOutboxProperties

    @MockitoBean
    private lateinit var s3: S3Service

    @MockitoBean
    private lateinit var ai: NutritionAnalysisClient

    @MockitoBean
    private lateinit var tokens: TokenService

    @MockitoSpyBean
    private lateinit var publisher: DbMealAnalysisPublisher

    private var userId = 0L
    private val result = NutritionAnalysisResult("샐러드", Nutrition(200, 20, 10, 9), "잘 먹었다냥", 1)

    @BeforeEach
    fun setUp() {
        // 앞선 테스트의 컨텍스트와 스케줄러는 종료된 상태에서 새 컨텍스트로 시작한다.
        queue.deleteAllInBatch()
        outboxes.deleteAllInBatch()
        meals.deleteAllInBatch()
        users.deleteAllInBatch()
        jdbc.update("INSERT IGNORE INTO icon (id, name, image_url) VALUES (1, '음식', 'test')")
        userId = users.save(User(AuthProvider.EMAIL, email = "scheduled@test.com")).id
        `when`(tokens.getAuthentication("owner")).thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(AuthUser(userId, "owner"), null, emptyList()),
        )
        `when`(s3.confirmUploadedMealImage(anyLong(), anyString(), anyLong())).thenAnswer { invocation ->
            invocation.getArgument<String>(1) to Instant.now()
        }
        `when`(ai.analyzeNutrition(anyString())).thenReturn(result)
        assertEquals(Duration.ofSeconds(1), analysisProperties.pollDelay)
        assertEquals(2, analysisProperties.concurrency)
        assertEquals(Duration.ofSeconds(3), outboxProperties.recoveryDelay)
    }

    @Test
    fun `실제 스케줄러가 두 작업씩 실행하고 빈자리가 생기면 나머지 요청을 처리한다`() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        `when`(ai.analyzeNutrition(anyString())).thenAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertTrue(Thread.currentThread().name.startsWith("meal-analysis-"))
            maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            started.countDown()
            try {
                check(release.await(15, TimeUnit.SECONDS))
                result
            } finally {
                active.decrementAndGet()
            }
        }

        val ids = try {
            val created = List(3) { createMeal() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            await().atMost(Duration.ofSeconds(5)).until { queue.count() == 3L }
            // 수동 poll 호출 없이 다음 스케줄에서도 세 번째 요청이 대기하는지 확인한다.
            await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(4)).untilAsserted {
                assertEquals(2, active.get())
                assertEquals(1, queue.findAll().count { it.status == MealAnalysisQueueStatus.READY })
                verify(ai, times(2)).analyzeNutrition(anyString())
            }
            created
        } finally {
            release.countDown()
        }

        ids.forEach(::awaitCompleted)
        assertEquals(2, maximum.get())
        assertTrue(queue.findAll().all { it.status == MealAnalysisQueueStatus.DONE })
        verify(ai, times(3)).analyzeNutrition(anyString())
    }

    @Test
    fun `즉시 전달 실패를 실제 복구 스케줄러가 전달하고 Consumer가 분석한다`() {
        val immediateFailed = AtomicBoolean(false)
        val recovered = AtomicBoolean(false)
        val target = AopTestUtils.getUltimateTargetObject<DbMealAnalysisPublisher>(publisher)
        doAnswer { invocation ->
            if (Thread.currentThread().name.startsWith("meal-outbox-")) {
                immediateFailed.set(true)
                error("즉시 전달 장애")
            }
            // 첫 복구 tick이 리스너보다 먼저 와도 즉시 전달 실패를 검증한다.
            check(immediateFailed.get())
            assertTrue(Thread.currentThread().name.startsWith("meal-scheduler-"))
            invocation.callRealMethod()
            recovered.set(true)
            null
        }.`when`(target).publish(any(MealAnalysisMessage::class.java) ?: MealAnalysisMessage(0, 0))

        val id = createMeal()
        awaitCompleted(id)

        assertTrue(immediateFailed.get())
        assertTrue(recovered.get())
        assertNotNull(outboxes.findAll().single().publishedAt)
        assertEquals(MealAnalysisQueueStatus.DONE, queue.findAll().single().status)
        verify(ai, times(1)).analyzeNutrition(anyString())
    }

    @Test
    fun `이벤트가 유실되어도 커밋된 Outbox만으로 분석까지 복구한다`() {
        val id = transaction.execute {
            val meal = meals.save(Meal(MealStatus.WAITING, "meals/$userId/lost.jpg", Instant.now(), userId))
            outboxes.save(MealOutbox(meal.id, Instant.now()))
            // 메모리 이벤트를 발행하지 않아 커밋 이후 이벤트가 유실된 상황을 재현한다.
            meal.id
        }

        awaitCompleted(id)

        assertNotNull(outboxes.findAll().single().publishedAt)
        assertEquals(MealAnalysisQueueStatus.DONE, queue.findAll().single().status)
        verify(ai, times(1)).analyzeNutrition(anyString())
    }

    @Test
    fun `분석 실패는 자동 재시도하지 않고 사용자 재시도 요청 이후에만 다시 실행한다`() {
        `when`(ai.analyzeNutrition(anyString())).thenThrow(IllegalStateException("AI 분석 실패"))
        val id = createMeal()
        await().atMost(Duration.ofSeconds(10)).until {
            meals.findById(id).orElseThrow().status == MealStatus.FAILED
        }
        val failedQueueId = queue.findAll().single().id

        await().during(Duration.ofMillis(2200)).atMost(Duration.ofSeconds(5)).untilAsserted {
            assertEquals(MealStatus.FAILED, meals.findById(id).orElseThrow().status)
            assertEquals(1L, outboxes.count())
            assertEquals(1L, queue.count())
            verify(ai, times(1)).analyzeNutrition(anyString())
        }
        doReturn(result).`when`(ai).analyzeNutrition(anyString())
        mvc.perform(post("/api/v1/meals/$id/analysis").header("Authorization", "Bearer owner"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("WAITING"))

        awaitCompleted(id)

        assertEquals(2L, outboxes.count())
        assertEquals(2L, queue.count())
        assertEquals(MealAnalysisQueueStatus.FAILED, queue.findById(failedQueueId).orElseThrow().status)
        assertEquals(1, queue.findAll().count { it.status == MealAnalysisQueueStatus.DONE })
        verify(ai, times(2)).analyzeNutrition(anyString())
    }

    private fun createMeal(): Long {
        val response = mvc.perform(
            post("/api/v1/meals")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageKey":"meals/$userId/${UUID.randomUUID()}.jpg"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("WAITING"))
            .andReturn().response.contentAsString
        return mapper.readTree(response).get("id").asLong()
    }

    private fun awaitCompleted(id: Long) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            mvc.perform(get("/api/v1/meals/$id/analysis").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
            assertEquals(result.nutrition.calory, meals.findById(id).orElseThrow().calory)
        }
    }
}
