package com.dandi.nyummy.worker.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealFoodRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.meal.repository.MealSnapshotRepository
import com.dandi.nyummy.meal.service.MealFoodService
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import com.dandi.nyummy.worker.config.WorkerProperties
import com.dandi.nyummy.worker.dto.MealAnalysisJobResult
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 인식 작업을 끝내고 영양 작업으로 넘기는 일이 실제 DB에서 되는지 확인한다.
 * 식사 하나에 작업은 하나(유니크 제약)라 지우고 새로 만드는 순서가 맞아야 하고, 미루는 것과 가져가는 것은 DB 시계로 하는 쿼리라
 * mock으로는 볼 수 없다.
 *
 * Worker를 켜면 polling이 이 테스트가 만든 작업을 가져가 실제 분석을 시작하므로, Worker 빈을 띄우지 않고 서비스를 직접 만든다.
 * 직접 만든 객체에는 트랜잭션 프록시가 없어 [TransactionTemplate]으로 감싸 부른다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MealAnalysisJobTransactionServiceIntegrationTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val mealRepository: MealRepository,
    private val mealSnapshotRepository: MealSnapshotRepository,
    private val mealFoodRepository: MealFoodRepository,
    private val mealAnalysisJobRepository: MealAnalysisJobRepository,
    mealFoodService: MealFoodService,
    catRepository: CatRepository,
    catService: CatService,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")

        private val NUTRITION_DELAY = Duration.ofMinutes(5)

        // 다른 테스트가 남긴 작업까지 모두 가져오도록 넉넉히 둔다.
        private const val LEASE_LIMIT = 100
    }

    private val transactionTemplate = TransactionTemplate(transactionManager)
    private val mealAnalysisJobTransactionService = MealAnalysisJobTransactionService(
        mealAnalysisJobRepository = mealAnalysisJobRepository,
        mealRepository = mealRepository,
        mealFoodService = mealFoodService,
        catRepository = catRepository,
        catService = catService,
        workerProperties = WorkerProperties(enabled = true),
        clock = clock,
    )

    private val recognition = MealRecognitionResult(
        inputCategory = MealInputCategory.FOOD_PHOTO,
        name = "불고기 백반",
        foods = listOf(
            MealFoodRecognitionResult(name = "불고기", portion = BigDecimal("1.5")),
            MealFoodRecognitionResult(name = "잡곡밥", portion = BigDecimal("1.0")),
        ),
        model = "gemini-recognition-test",
        promptVersion = "v1",
    )

    /** 식사 기록 요청 안에서 인식을 끝내지 못한 식사(RECOGNIZING)와 그 인식 작업을 만든다. */
    private fun createRecognizingMeal(email: String): Meal {
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = email)).id
        val meal = mealRepository.save(
            Meal(
                status = MealStatus.RECOGNIZING,
                imageKey = "meals/$userId/2026/11/14/$email.jpg",
                mealAt = Instant.now(clock),
                userId = userId,
                analysisVersion = MealAnalysisVersion.V2,
            ),
        )
        mealAnalysisJobRepository.save(
            MealAnalysisJob(mealId = meal.id, language = Language.JA, category = MealAnalysisJobCategory.RECOGNITION),
        )

        return meal
    }

    private fun leaseJob(mealId: Long): MealAnalysisJobResult? = transactionTemplate.execute {
        mealAnalysisJobTransactionService.leaseMealAnalysisJobs(LEASE_LIMIT)
    }!!.singleOrNull { it.mealId == mealId }

    private fun confirmMealRecognition(job: MealAnalysisJobResult): Boolean = transactionTemplate.execute {
        mealAnalysisJobTransactionService.confirmMealRecognition(job, recognition, NUTRITION_DELAY)
    }!!

    private fun findJob(mealId: Long): MealAnalysisJob? =
        mealAnalysisJobRepository.findAll().singleOrNull { it.mealId == mealId }

    @Test
    fun `인식 작업을 가져가도 식사는 RECOGNIZING 그대로다`() {
        val meal = createRecognizingMeal("lease@nyummy.co.kr")

        val job = assertNotNull(leaseJob(meal.id))

        assertEquals(MealAnalysisJobCategory.RECOGNITION, job.category)
        assertEquals(1, job.leaseCount)
        assertEquals(MealStatus.RECOGNIZING, mealRepository.findById(meal.id).orElseThrow().status)
    }

    @Test
    fun `인식을 확정하면 인식 작업 행은 사라지고 미뤄진 영양 작업 행이 새로 생긴다`() {
        val meal = createRecognizingMeal("confirm@nyummy.co.kr")
        val recognitionJob = assertNotNull(leaseJob(meal.id))

        assertTrue(confirmMealRecognition(recognitionJob))

        val savedMeal = mealRepository.findById(meal.id).orElseThrow()
        assertEquals(MealStatus.WAITING, savedMeal.status)
        assertEquals("불고기 백반", savedMeal.name)
        assertEquals(MealInputCategory.FOOD_PHOTO, savedMeal.inputCategory)

        val mealSnapshot = mealSnapshotRepository.findAll().single { it.mealId == meal.id }
        assertEquals(MealRecognitionCategory.WORKER, mealSnapshot.recognitionCategory)
        val mealFoods = mealFoodRepository.findAll().filter { it.mealId == meal.id }.sortedBy { it.displayOrder }
        assertEquals(listOf("불고기", "잡곡밥"), mealFoods.map { it.name })

        // 같은 식사의 작업은 여전히 하나이고, 인식 작업과 다른 새 행이다.
        val nutritionJob = assertNotNull(findJob(meal.id))
        assertNotEquals(recognitionJob.jobId, nutritionJob.id)
        assertEquals(MealAnalysisJobCategory.NUTRITION, nutritionJob.category)
        assertEquals(Language.JA, nutritionJob.language)
        // 영양 작업의 시도 횟수는 인식 작업과 따로 0부터 센다.
        assertEquals(0, nutritionJob.leaseCount)
        // DB 시계와 JVM 시계의 차이를 감안해 1분 여유를 둔다.
        val leaseExpiresAt = assertNotNull(nutritionJob.leaseExpiresAt)
        assertTrue(leaseExpiresAt > Instant.now().plus(NUTRITION_DELAY).minus(Duration.ofMinutes(1)))
    }

    @Test
    fun `미뤄진 영양 작업은 지연 시간이 지나기 전에는 가져가지 않는다`() {
        val meal = createRecognizingMeal("delayed@nyummy.co.kr")
        confirmMealRecognition(assertNotNull(leaseJob(meal.id)))

        assertNull(leaseJob(meal.id))
        assertEquals(MealStatus.WAITING, mealRepository.findById(meal.id).orElseThrow().status)
    }

    @Test
    fun `늦게 끝난 인식 Worker는 이미 넘어간 식사의 영양 작업과 음식 줄을 건드리지 못한다`() {
        val meal = createRecognizingMeal("fencing@nyummy.co.kr")
        val recognitionJob = assertNotNull(leaseJob(meal.id))
        confirmMealRecognition(recognitionJob)
        val nutritionJob = assertNotNull(findJob(meal.id))

        // 같은 인식 작업을 쥔 Worker가 뒤늦게 결과를 쓰거나 실패로 끝내려 한다.
        assertFalse(confirmMealRecognition(recognitionJob))
        val pushResult = transactionTemplate.execute {
            mealAnalysisJobTransactionService.failMealAnalysis(recognitionJob)
        }

        assertNull(pushResult)
        assertEquals(nutritionJob.id, findJob(meal.id)?.id)
        assertEquals(MealStatus.WAITING, mealRepository.findById(meal.id).orElseThrow().status)
        assertEquals(1, mealSnapshotRepository.findAll().count { it.mealId == meal.id })
        assertEquals(2, mealFoodRepository.findAll().count { it.mealId == meal.id })
    }
}
