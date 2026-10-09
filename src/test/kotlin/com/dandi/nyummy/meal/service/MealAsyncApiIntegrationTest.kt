package com.dandi.nyummy.meal.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisClient
import com.dandi.nyummy.infra.ai.nutrition.NutritionAnalysisResult
import com.dandi.nyummy.infra.aws.s3.S3Service
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealAnalysisQueueStatus
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.queue.DbMealAnalysisConsumer
import com.dandi.nyummy.meal.repository.MealAnalysisQueueRepository
import com.dandi.nyummy.meal.repository.MealOutboxRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.jwt.TokenService
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MealAsyncApiIntegrationTest {
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
    private lateinit var consumer: DbMealAnalysisConsumer

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    @Qualifier("outboxExecutor")
    private lateinit var outboxExecutor: ThreadPoolTaskExecutor

    @Autowired
    @Qualifier("mealAnalysisExecutor")
    private lateinit var analysisExecutor: ThreadPoolTaskExecutor

    @MockitoBean
    private lateinit var s3: S3Service

    @MockitoBean
    private lateinit var ai: NutritionAnalysisClient

    @MockitoBean
    private lateinit var tokens: TokenService

    private var userId = 0L
    private val capturedAt = Instant.parse("2026-10-04T01:00:00Z")
    private val analysis = NutritionAnalysisResult("샐러드", Nutrition(200, 20, 10, 9), "잘 먹었다냥", 1)

    @BeforeEach
    fun setUp() {
        awaitBackground()
        queue.deleteAllInBatch()
        outboxes.deleteAllInBatch()
        meals.deleteAllInBatch()
        users.deleteAllInBatch()
        jdbc.update("INSERT IGNORE INTO icon (id, name, image_url) VALUES (1, '음식', 'test')")
        userId = users.save(User(AuthProvider.EMAIL, email = "meal-api@test.com")).id
        `when`(tokens.getAuthentication("owner")).thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(AuthUser(userId, "owner"), null, emptyList()),
        )
        `when`(tokens.getAuthentication("other")).thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(AuthUser(userId + 1000, "other"), null, emptyList()),
        )
        `when`(s3.confirmUploadedMealImage(anyLong(), anyString(), anyLong())).thenAnswer { invocation ->
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            invocation.getArgument<String>(1) to capturedAt
        }
        `when`(ai.analyzeNutrition(anyString())).thenReturn(analysis)
    }

    @AfterEach
    fun awaitBackground() {
        outboxExecutor.submit {}.get(10, TimeUnit.SECONDS)
        await().atMost(Duration.ofSeconds(10)).until {
            analysisExecutor.activeCount == 0 && analysisExecutor.threadPoolExecutor.queue.isEmpty()
        }
    }

    @Test
    fun `생성 API는 WAITING을 응답하고 큐를 통해 ANALYZING과 COMPLETED로 전이한다`() {
        val mealId = createMeal()
        awaitBackground()
        val saved = meals.findById(mealId).orElseThrow()
        assertEquals(capturedAt, saved.mealAt)
        assertEquals(MealStatus.WAITING, saved.status)
        assertNotNull(outboxes.findAll().single().publishedAt)
        assertEquals(MealAnalysisQueueStatus.READY, queue.findAll().single().status)
        verify(ai, never()).analyzeNutrition(anyString())

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(ai.analyzeNutrition(anyString())).thenAnswer {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            analysis
        }
        try {
            consumer.poll()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            mvc.perform(get("/api/v1/meals/$mealId/analysis").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ANALYZING"))
        } finally {
            release.countDown()
        }
        awaitBackground()
        mvc.perform(get("/api/v1/meals/$mealId/analysis").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
        assertEquals(analysis.nutrition.calory, meals.findById(mealId).orElseThrow().calory)
        assertEquals(MealAnalysisQueueStatus.DONE, queue.findAll().single().status)
    }

    @Test
    fun `Outbox 저장 실패 시 식사 생성도 롤백하고 이벤트를 전달하지 않는다`() {
        rejectOutboxWrites {
            mvc.perform(
                post("/api/v1/meals")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"imageKey":"meals/$userId/rollback.jpg"}"""),
            ).andExpect(status().isInternalServerError)
        }
        awaitBackground()

        assertEquals(0L, meals.count())
        assertEquals(0L, outboxes.count())
        assertEquals(0L, queue.count())
        verify(ai, never()).analyzeNutrition(anyString())
    }

    @Test
    fun `이미지 검증 실패 시 식사와 Outbox를 저장하지 않는다`() {
        `when`(s3.confirmUploadedMealImage(anyLong(), anyString(), anyLong()))
            .thenThrow(BusinessException(MealErrorCode.STALE_IMAGE))
        mvc.perform(
            post("/api/v1/meals")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageKey":"meals/$userId/stale.jpg"}"""),
        ).andExpect(status().isBadRequest)

        assertEquals(0L, meals.count())
        assertEquals(0L, outboxes.count())
        assertEquals(0L, queue.count())
    }

    @Test
    fun `동일 이미지 동시 생성은 한 건만 성공하고 나머지는 중복 오류를 반환한다`() {
        val barrier = CyclicBarrier(2)
        `when`(s3.confirmUploadedMealImage(anyLong(), anyString(), anyLong())).thenAnswer { invocation ->
            barrier.await(5, TimeUnit.SECONDS)
            invocation.getArgument<String>(1) to capturedAt
        }
        val responses = concurrently {
            mvc.perform(
                post("/api/v1/meals")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"imageKey":"meals/$userId/shared.jpg"}"""),
            ).andReturn().response.status
        }
        awaitBackground()

        assertEquals(listOf(201, 409), responses.sorted())
        assertEquals(1L, meals.count())
        assertEquals(1L, outboxes.count())
        assertEquals(1L, queue.count())
    }

    @Test
    fun `실패한 식사를 재시도하면 기존 실패 작업을 보존하고 새 Outbox와 큐를 생성한다`() {
        val mealId = createMeal()
        awaitBackground()
        `when`(ai.analyzeNutrition(anyString())).thenThrow(IllegalStateException("분석 실패"))
        consumer.poll()
        awaitBackground()
        assertEquals(MealStatus.FAILED, meals.findById(mealId).orElseThrow().status)
        val previous = queue.findAll().single()

        mvc.perform(post("/api/v1/meals/$mealId/analysis").header("Authorization", "Bearer owner"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("WAITING"))
        awaitBackground()

        assertEquals(2L, outboxes.count())
        assertEquals(2L, queue.count())
        assertEquals(MealAnalysisQueueStatus.FAILED, queue.findById(previous.id).orElseThrow().status)
        assertEquals(1, queue.findAll().count { it.status == MealAnalysisQueueStatus.READY })

        // 예외를 반환하도록 설정된 mock을 재호출하지 않고 응답을 교체한다.
        org.mockito.Mockito.doReturn(analysis).`when`(ai).analyzeNutrition(anyString())
        consumer.poll()
        awaitBackground()
        assertEquals(MealStatus.COMPLETED, meals.findById(mealId).orElseThrow().status)
        verify(ai, times(2)).analyzeNutrition(anyString())
    }

    @Test
    fun `동시 재시도는 한 요청만 접수한다`() {
        val meal = saveMeal(MealStatus.FAILED)
        val responses = concurrently {
            mvc.perform(post("/api/v1/meals/${meal.id}/analysis").header("Authorization", "Bearer owner"))
                .andReturn().response.status
        }
        awaitBackground()

        assertEquals(listOf(202, 409), responses.sorted())
        assertEquals(MealStatus.WAITING, meals.findById(meal.id).orElseThrow().status)
        assertEquals(1L, outboxes.count())
        assertEquals(1L, queue.count())
    }

    @Test
    fun `재시도 Outbox 저장 실패 시 식사는 FAILED를 유지한다`() {
        val meal = saveMeal(MealStatus.FAILED)
        rejectOutboxWrites {
            mvc.perform(post("/api/v1/meals/${meal.id}/analysis").header("Authorization", "Bearer owner"))
                .andExpect(status().isInternalServerError)
        }
        awaitBackground()

        assertEquals(MealStatus.FAILED, meals.findById(meal.id).orElseThrow().status)
        assertEquals(0L, outboxes.count())
        assertEquals(0L, queue.count())
    }

    @ParameterizedTest
    @EnumSource(MealStatus::class, names = ["FAILED"], mode = EnumSource.Mode.EXCLUDE)
    fun `FAILED 외 상태에서는 재시도하지 않는다`(mealStatus: MealStatus) {
        val meal = saveMeal(mealStatus)
        mvc.perform(post("/api/v1/meals/${meal.id}/analysis").header("Authorization", "Bearer owner"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(MealErrorCode.ANALYSIS_NOT_RETRYABLE.code))

        assertEquals(mealStatus, meals.findById(meal.id).orElseThrow().status)
        assertEquals(0L, outboxes.count())
    }

    @Test
    fun `타인과 삭제된 식사의 상태 조회 및 재시도를 허용하지 않는다`() {
        val meal = saveMeal(MealStatus.FAILED)
        mvc.perform(get("/api/v1/meals/${meal.id}/analysis").header("Authorization", "Bearer other"))
            .andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/meals/${meal.id}/analysis").header("Authorization", "Bearer other"))
            .andExpect(status().isNotFound)

        mvc.perform(delete("/api/v1/meals/${meal.id}").header("Authorization", "Bearer owner"))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/v1/meals/${meal.id}/analysis").header("Authorization", "Bearer owner"))
            .andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/meals/${meal.id}/analysis").header("Authorization", "Bearer owner"))
            .andExpect(status().isNotFound)
        assertEquals(0L, outboxes.count())
    }

    @Test
    fun `분석 중 삭제 요청은 결과 저장 후에도 삭제를 유지한다`() {
        val mealId = createMeal()
        awaitBackground()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(ai.analyzeNutrition(anyString())).thenAnswer {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            analysis
        }
        try {
            consumer.poll()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            mvc.perform(delete("/api/v1/meals/$mealId").header("Authorization", "Bearer owner"))
                .andExpect(status().isNoContent)
        } finally {
            release.countDown()
        }
        awaitBackground()

        val meal = meals.findById(mealId).orElseThrow()
        assertNotNull(meal.deletedAt)
        assertNull(meal.calory)
        assertEquals(MealAnalysisQueueStatus.FAILED, queue.findAll().single().status)
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

    private fun saveMeal(status: MealStatus): Meal =
        meals.saveAndFlush(Meal(status, "meals/$userId/${UUID.randomUUID()}.jpg", capturedAt, userId))

    private fun rejectOutboxWrites(block: () -> Unit) {
        jdbc.execute(
            "ALTER TABLE meal_outbox ADD CONSTRAINT test_reject_outbox CHECK (meal_id < 0)",
        )
        try {
            block()
        } finally {
            jdbc.execute("ALTER TABLE meal_outbox DROP CHECK test_reject_outbox")
        }
    }

    private fun concurrently(action: () -> Int): List<Int> {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = List(2) {
                executor.submit<Int> {
                    check(start.await(5, TimeUnit.SECONDS))
                    action()
                }
            }
            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
