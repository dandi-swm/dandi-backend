package com.dandi.nyummy.meal.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealRecognitionOutcome
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.RewardBoxResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.enum.MealRecognitionDeferralCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealAnalysisJobRepository
import com.dandi.nyummy.meal.repository.MealFoodRepository
import com.dandi.nyummy.meal.repository.MealFoodSnapshotRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.meal.repository.MealSnapshotRepository
import com.dandi.nyummy.user.entity.Profile
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v2 식사 기록이 새 테이블(스냅샷·음식 줄)과 작업에 실제로 어떻게 남는지 실제 DB로 확인한다.
 * 영양 작업을 미루는 것은 DB 시계로 하는 쿼리라 mock으로는 볼 수 없다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MealTransactionServiceV2IntegrationTest @Autowired constructor(
    private val mealTransactionService: MealTransactionService,
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val mealRepository: MealRepository,
    private val mealSnapshotRepository: MealSnapshotRepository,
    private val mealFoodSnapshotRepository: MealFoodSnapshotRepository,
    private val mealFoodRepository: MealFoodRepository,
    private val mealAnalysisJobRepository: MealAnalysisJobRepository,
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
    }

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

    private fun createMealV2(email: String, status: MealStatus, outcome: MealRecognitionOutcome): Meal {
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = email)).id
        profileRepository.save(Profile(userId = userId))
        val now = Instant.now(clock)
        val meal = Meal(
            status = status,
            imageKey = "meals/$userId/2026/11/14/$email.jpg",
            mealAt = now,
            userId = userId,
            analysisVersion = MealAnalysisVersion.V2,
        )

        mealTransactionService.createMealV2(
            meal = meal,
            outcome = outcome,
            rewardBox = RewardBoxResult(RewardBoxCategory.GOLD, 412),
            rewardMailExpiresAt = now.plus(Duration.ofDays(7)),
            nutritionDelay = NUTRITION_DELAY,
            language = Language.KO,
        )

        return meal
    }

    @Test
    fun `음식으로 인식한 식사는 스냅샷과 음식 줄이 남고 영양 작업은 지연 시간 뒤에야 가져갈 수 있다`() {
        val meal =
            createMealV2("recognized@nyummy.co.kr", MealStatus.WAITING, MealRecognitionOutcome.Recognized(recognition))

        val savedMeal = mealRepository.findById(meal.id).orElseThrow()
        assertEquals(MealAnalysisVersion.V2, savedMeal.analysisVersion)

        val mealSnapshot = mealSnapshotRepository.findAll().single { it.mealId == meal.id }
        assertEquals("불고기 백반", mealSnapshot.name)
        assertEquals(MealRecognitionCategory.SYNC, mealSnapshot.recognitionCategory)
        assertEquals(MealInputCategory.FOOD_PHOTO, mealSnapshot.inputCategory)

        val mealFoodSnapshots = mealFoodSnapshotRepository.findAll()
            .filter { it.mealSnapshotId == mealSnapshot.id }
            .sortedBy { it.displayOrder }
        val mealFoods = mealFoodRepository.findAll().filter { it.mealId == meal.id }.sortedBy { it.displayOrder }
        assertEquals(listOf("불고기", "잡곡밥"), mealFoods.map { it.name })
        assertEquals(listOf(BigDecimal("1.5"), BigDecimal("1.0")), mealFoods.map { it.portion })
        // 음식 줄은 짝이 되는 스냅샷 줄을 가리키고, 영양은 아직 구하지 않았다.
        assertEquals(mealFoodSnapshots.map { it.id }, mealFoods.map { it.mealFoodSnapshotId })
        assertTrue(mealFoods.all { it.servingNutrition == null && !it.isNutritionFixed && it.iconId == null })

        val job = mealAnalysisJobRepository.findAll().single { it.mealId == meal.id }
        assertEquals(MealAnalysisJobCategory.NUTRITION, job.category)
        assertEquals(0, job.leaseCount)
        // lease_expires_at이 미래라 Worker가 그때까지 가져가지 않는다. DB 시계와 JVM 시계의 차이를 감안해 1분 여유를 둔다.
        val leaseExpiresAt = assertNotNull(job.leaseExpiresAt)
        assertTrue(leaseExpiresAt > Instant.now().plus(NUTRITION_DELAY).minus(Duration.ofMinutes(1)))
        // 지표가 기다린 시간을 셀 기준으로, 가져갈 수 있게 되는 시각을 따로 남긴다.
        assertEquals(leaseExpiresAt, job.availableAt)
    }

    @Test
    fun `요청 안에서 인식을 끝내지 못한 식사는 바로 가져갈 인식 작업만 남는다`() {
        val meal = createMealV2(
            "deferred@nyummy.co.kr",
            MealStatus.RECOGNIZING,
            MealRecognitionOutcome.Deferred(MealRecognitionDeferralCategory.TIMEOUT),
        )

        assertEquals(MealStatus.RECOGNIZING, mealRepository.findById(meal.id).orElseThrow().status)
        assertTrue(mealSnapshotRepository.findAll().none { it.mealId == meal.id })
        assertTrue(mealFoodRepository.findAll().none { it.mealId == meal.id })

        val job = mealAnalysisJobRepository.findAll().single { it.mealId == meal.id }
        assertEquals(MealAnalysisJobCategory.RECOGNITION, job.category)
        assertNull(job.leaseExpiresAt)
        assertNull(job.availableAt)
    }

    @Test
    fun `식사 행을 지우면 스냅샷과 음식 줄과 작업이 함께 지워진다`() {
        val meal =
            createMealV2("cascade@nyummy.co.kr", MealStatus.WAITING, MealRecognitionOutcome.Recognized(recognition))

        mealRepository.deleteById(meal.id)

        assertTrue(mealSnapshotRepository.findAll().none { it.mealId == meal.id })
        assertTrue(mealFoodRepository.findAll().none { it.mealId == meal.id })
        assertTrue(mealAnalysisJobRepository.findAll().none { it.mealId == meal.id })
    }
}
