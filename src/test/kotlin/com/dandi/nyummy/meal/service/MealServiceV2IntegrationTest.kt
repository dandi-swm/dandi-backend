package com.dandi.nyummy.meal.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.MealFoodNutritionRequest
import com.dandi.nyummy.meal.dto.MealFoodRecognitionResult
import com.dandi.nyummy.meal.dto.MealFoodRequest
import com.dandi.nyummy.meal.dto.MealRecognitionResult
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.UpdateMealFoodsRequest
import com.dandi.nyummy.meal.entity.Icon
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealAnalysisJob
import com.dandi.nyummy.meal.entity.MealFood
import com.dandi.nyummy.meal.enum.MealAnalysisJobCategory
import com.dandi.nyummy.meal.enum.MealAnalysisVersion
import com.dandi.nyummy.meal.enum.MealInputCategory
import com.dandi.nyummy.meal.enum.MealRecognitionCategory
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.IconRepository
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v2 식사를 고치는 요청이 음식 줄과 작업 행을 실제 DB에서 어떻게 바꾸는지 확인한다.
 * 식사 하나에 작업은 하나(유니크 제약)라 지우고 새로 만드는 순서와 잠그는 순서가 맞아야 하고,
 * 요청이 겹쳤을 때 데드락이나 유니크 제약 위반이 나지 않는지는 mock으로 볼 수 없다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MealServiceV2IntegrationTest @Autowired constructor(
    private val mealService: MealService,
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val mealRepository: MealRepository,
    private val mealFoodRepository: MealFoodRepository,
    private val mealAnalysisJobRepository: MealAnalysisJobRepository,
    private val iconRepository: IconRepository,
    private val mealFoodService: MealFoodService,
    private val mealSnapshotRepository: MealSnapshotRepository,
    private val mealFoodSnapshotRepository: MealFoodSnapshotRepository,
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

        private const val NUTRITION_DELAY_SECONDS = 300L
    }

    private val transactionTemplate = TransactionTemplate(transactionManager)

    private val bulgogiServingNutrition = ServingNutrition(
        calory = BigDecimal("350.00"),
        carbs = BigDecimal("15.50"),
        protein = BigDecimal("25.25"),
        fat = BigDecimal("20.00"),
    )
    private val riceServingNutrition = ServingNutrition(
        calory = BigDecimal("300.00"),
        carbs = BigDecimal("65.00"),
        protein = BigDecimal("6.00"),
        fat = BigDecimal("1.00"),
    )

    private fun createIconId(name: String): Long =
        iconRepository.save(Icon(name = name, imageUrl = "https://cdn.test/$name.png")).id

    /**
     * 영양 계산이 끝난 V2 식사(불고기 1.5인분, 잡곡밥 1인분). 작업 행은 없다.
     *
     * @return 식사와 그 음식 줄(순서대로)
     */
    private fun createCompletedMeal(email: String): Pair<Meal, List<MealFood>> {
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = email)).id
        profileRepository.save(Profile(userId = userId))
        val bulgogiIconId = createIconId("$email-bulgogi")
        val riceIconId = createIconId("$email-rice")
        val meal = mealRepository.save(
            Meal(
                status = MealStatus.COMPLETED,
                imageKey = "meals/$userId/2026/11/14/$email.jpg",
                mealAt = Instant.now(clock),
                userId = userId,
                analysisVersion = MealAnalysisVersion.V2,
            ).apply {
                updateName("불고기 백반")
                updateNutrition(Nutrition(calory = 825, carbs = 88, protein = 44, fat = 31))
                updateIconId(bulgogiIconId)
                updateCatComment("든든하게 먹었다냥")
            },
        )
        val mealFoods = mealFoodRepository.saveAll(
            listOf(
                MealFood(meal.id, null, 1, "불고기", BigDecimal("1.5")).apply {
                    updateServingNutrition(bulgogiServingNutrition)
                    updateIconId(bulgogiIconId)
                },
                MealFood(meal.id, null, 2, "잡곡밥", BigDecimal("1.0")).apply {
                    updateServingNutrition(riceServingNutrition)
                    updateIconId(riceIconId)
                },
            ),
        )

        return meal to mealFoods
    }

    /**
     * 인식은 끝났고 영양 계산은 아직인 V2 식사(불고기 1.5인분, 잡곡밥 1인분). 1인분·아이콘·코멘트가 없고 작업 행도 없다.
     *
     * @return 식사와 그 음식 줄(순서대로)
     */
    private fun createWaitingMeal(email: String): Pair<Meal, List<MealFood>> {
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = email)).id
        profileRepository.save(Profile(userId = userId))
        val meal = mealRepository.save(
            Meal(
                status = MealStatus.WAITING,
                imageKey = "meals/$userId/2026/11/14/$email.jpg",
                mealAt = Instant.now(clock),
                userId = userId,
                analysisVersion = MealAnalysisVersion.V2,
            ).apply { updateName("불고기 백반") },
        )
        val mealFoods = mealFoodRepository.saveAll(
            listOf(
                MealFood(meal.id, null, 1, "불고기", BigDecimal("1.5")),
                MealFood(meal.id, null, 2, "잡곡밥", BigDecimal("1.0")),
            ),
        )

        return meal to mealFoods
    }

    private fun findJob(mealId: Long): MealAnalysisJob? =
        mealAnalysisJobRepository.findAll().singleOrNull { it.mealId == mealId }

    private fun createSameFoodsRequest(mealFoods: List<MealFood>) = UpdateMealFoodsRequest(
        foods = mealFoods.map { MealFoodRequest(mealFoodId = it.id, name = it.name, portion = it.portion) },
    )

    @Test
    fun `완료된 식사의 목록을 고쳐 보내면 줄이 바뀌고 바로 가져갈 영양 작업이 생기며 식사는 값 없는 WAITING이 된다`() {
        val (meal, mealFoods) = createCompletedMeal("update@nyummy.co.kr")
        val (bulgogi, rice) = mealFoods
        val kimchiIconId = createIconId("update-kimchi")

        // 잡곡밥을 맨 위로 올리고 인분을 바꾸고, 불고기는 이름을 바꾸고, 김치를 영양·아이콘과 함께 더한다.
        mealService.updateMealFoodsV2(
            userId = meal.userId,
            mealId = meal.id,
            request = UpdateMealFoodsRequest(
                foods = listOf(
                    MealFoodRequest(mealFoodId = rice.id, name = "잡곡밥", portion = BigDecimal("2.0")),
                    MealFoodRequest(mealFoodId = bulgogi.id, name = "제육볶음", portion = BigDecimal("1.5")),
                    MealFoodRequest(
                        mealFoodId = null,
                        name = "김치",
                        portion = BigDecimal("0.5"),
                        nutrition = MealFoodNutritionRequest(calory = 15, carbs = 3, protein = 1, fat = 0),
                        iconId = kimchiIconId,
                    ),
                ),
            ),
            language = Language.JA,
        )

        val savedMealFoods = mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(meal.id)
        assertEquals(listOf("잡곡밥", "제육볶음", "김치"), savedMealFoods.map { it.name })
        assertEquals(listOf(1, 2, 3), savedMealFoods.map { it.displayOrder })
        val (savedRice, savedPork, savedKimchi) = savedMealFoods
        // 인분만 바꾼 줄은 1인분과 아이콘이 그대로다.
        assertEquals(rice.id, savedRice.id)
        assertEquals(BigDecimal("2.0"), savedRice.portion)
        assertEquals(riceServingNutrition, savedRice.servingNutrition)
        assertNotNull(savedRice.iconId)
        // 이름을 바꾼 줄은 다시 구하도록 비운다.
        assertEquals(bulgogi.id, savedPork.id)
        assertNull(savedPork.servingNutrition)
        assertNull(savedPork.iconId)
        // 새 줄은 보낸 영양을 인분으로 나눈 1인분과 고른 아이콘으로 고정한다. 15 ÷ 0.5 = 30
        assertEquals(BigDecimal("30.00"), savedKimchi.servingNutrition?.calory)
        assertTrue(savedKimchi.isNutritionFixed && savedKimchi.isIconFixed)
        assertEquals(kimchiIconId, savedKimchi.iconId)

        val job = assertNotNull(findJob(meal.id))
        assertEquals(MealAnalysisJobCategory.NUTRITION, job.category)
        assertEquals(Language.JA, job.language)
        assertEquals(0, job.leaseCount)
        assertNull(job.leaseExpiresAt)
        assertNull(job.availableAt)

        val savedMeal = mealRepository.findById(meal.id).orElseThrow()
        assertEquals(MealStatus.WAITING, savedMeal.status)
        assertNull(savedMeal.calory)
        assertNull(savedMeal.iconId)
        assertEquals("불고기 백반", savedMeal.name)
        assertEquals("든든하게 먹었다냥", savedMeal.catComment)
    }

    @Test
    fun `완료된 식사의 인분만 바꾸고 줄을 지워 보내면 작업 없이 그 자리에서 합계를 다시 계산해 COMPLETED로 남는다`() {
        val (meal, mealFoods) = createCompletedMeal("sync@nyummy.co.kr")
        val bulgogi = mealFoods.first()

        // 잡곡밥을 지우고 불고기를 2인분으로 바꾼다. 새로 구할 값이 없다.
        val response = mealService.updateMealFoodsV2(
            userId = meal.userId,
            mealId = meal.id,
            request = UpdateMealFoodsRequest(
                foods = listOf(MealFoodRequest(mealFoodId = bulgogi.id, name = "불고기", portion = BigDecimal("2.0"))),
            ),
            language = Language.KO,
        )

        assertEquals(MealStatus.COMPLETED, response.status)
        assertEquals(listOf("불고기"), mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(meal.id).map { it.name })
        val savedMeal = mealRepository.findById(meal.id).orElseThrow()
        assertEquals(MealStatus.COMPLETED, savedMeal.status)
        // 350 × 2 = 700, 15.5 × 2 = 31, 25.25 × 2 = 50.5 → 51, 20 × 2 = 40
        assertEquals(700, savedMeal.calory)
        assertEquals(31, savedMeal.carbs)
        assertEquals(51, savedMeal.protein)
        assertEquals(40, savedMeal.fat)
        assertEquals(bulgogi.iconId, savedMeal.iconId)
        assertEquals("든든하게 먹었다냥", savedMeal.catComment)
        assertNull(findJob(meal.id))
    }

    @Test
    fun `5분을 기다리던 작업이 있으면 지우고 바로 가져갈 새 작업으로 바꾼다`() {
        val (meal, mealFoods) = createWaitingMeal("replace-job@nyummy.co.kr")
        val waitingJob = transactionTemplate.execute {
            val job = mealAnalysisJobRepository.save(
                MealAnalysisJob(mealId = meal.id, language = Language.KO, category = MealAnalysisJobCategory.NUTRITION),
            )
            mealAnalysisJobRepository.updateAvailableAt(job.id, NUTRITION_DELAY_SECONDS)
            job
        }!!

        mealService.updateMealFoodsV2(meal.userId, meal.id, createSameFoodsRequest(mealFoods), Language.KO)

        // 같은 식사의 작업은 여전히 하나이고, 기다리던 작업과 다른 새 행이다.
        val job = assertNotNull(findJob(meal.id))
        assertNotEquals(waitingJob.id, job.id)
        assertNull(job.leaseExpiresAt)
        assertNull(job.availableAt)
    }

    @Test
    fun `같은 식사에 동시에 두 번 보내도 둘 다 받고 작업은 하나다`() {
        val (meal, mealFoods) = createWaitingMeal("double-submit@nyummy.co.kr")
        val request = createSameFoodsRequest(mealFoods)

        // 첫 회는 작업 행이 없는 식사에, 다음 회부터는 앞 회가 만든 작업 행이 있는 식사에 겹쳐 보낸다.
        repeat(5) { round ->
            val failures =
                runConcurrently(2) { mealService.updateMealFoodsV2(meal.userId, meal.id, request, Language.KO) }

            assertTrue(failures.isEmpty(), "$round 회 실패: $failures")
            assertEquals(1, mealAnalysisJobRepository.findAll().count { it.mealId == meal.id })
        }
        assertEquals(2, mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(meal.id).size)
    }

    @Test
    fun `서로 다른 사용자가 작업 행이 없는 식사에 동시에 보내도 데드락 없이 모두 받는다`() {
        // 작업 행을 meal_id로 바로 지우면 없는 행에 구간 잠금이 걸려, 이 요청들이 서로의 INSERT를 막아 데드락이 난다.
        val meals = (1..8).map { createWaitingMeal("gap-$it@nyummy.co.kr") }
        val requests = meals.map { (meal, mealFoods) -> meal to createSameFoodsRequest(mealFoods) }

        repeat(3) { round ->
            // 앞 회가 만든 작업 행을 지워 다시 "작업 행 없음"에서 시작한다.
            transactionTemplate.execute { mealAnalysisJobRepository.deleteAllInBatch() }

            val failures = runConcurrently(requests.size) { index ->
                val (meal, request) = requests[index]
                mealService.updateMealFoodsV2(meal.userId, meal.id, request, Language.KO)
            }

            assertTrue(failures.isEmpty(), "$round 회 실패: $failures")
            assertEquals(
                meals.size,
                mealAnalysisJobRepository.findAll().count { job ->
                    meals.any {
                        it.first.id ==
                            job.mealId
                    }
                },
            )
        }
    }

    @Test
    fun `5분을 기다리던 식사를 지우면 작업 행이 사라지고 식사는 삭제 시각만 남으며 음식 줄은 그대로다`() {
        val (meal, _) = createWaitingMeal("delete@nyummy.co.kr")
        transactionTemplate.execute {
            val job = mealAnalysisJobRepository.save(
                MealAnalysisJob(mealId = meal.id, language = Language.KO, category = MealAnalysisJobCategory.NUTRITION),
            )
            mealAnalysisJobRepository.updateAvailableAt(job.id, NUTRITION_DELAY_SECONDS)
        }

        mealService.deleteMealV2(meal.userId, meal.id)

        assertNull(findJob(meal.id))
        val savedMeal = mealRepository.findById(meal.id).orElseThrow()
        assertNotNull(savedMeal.deletedAt)
        assertEquals(MealStatus.WAITING, savedMeal.status)
        assertEquals(2, mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(meal.id).size)
    }

    @Test
    fun `작업 행이 없는 식사의 기록을 취소해도 삭제 시각이 남는다`() {
        val (meal, _) = createCompletedMeal("cancel@nyummy.co.kr")

        mealService.cancelMealV2(meal.userId, meal.id)

        assertNotNull(mealRepository.findById(meal.id).orElseThrow().deletedAt)
        assertNull(findJob(meal.id))
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

    @Test
    fun `실패한 식사를 재분석하면 음식 줄과 스냅샷이 스냅샷 줄까지 사라지고 바로 가져갈 인식 작업이 생긴다`() {
        // 인식까지 됐다가(스냅샷·음식 줄 있음) 영양 계산에서 실패한 식사
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = "retry@nyummy.co.kr")).id
        profileRepository.save(Profile(userId = userId))
        val meal = mealRepository.save(
            Meal(
                status = MealStatus.FAILED,
                imageKey = "meals/$userId/2026/11/14/retry.jpg",
                mealAt = Instant.now(clock),
                userId = userId,
                analysisVersion = MealAnalysisVersion.V2,
            ).apply { updateName("불고기 백반") },
        )
        mealFoodService.createMealFoods(meal.id, recognition, MealRecognitionCategory.SYNC)
        val mealSnapshotId = mealSnapshotRepository.findByMealId(meal.id)!!.id

        val response = mealService.retryNutritionAnalysisV2(userId, meal.id, Language.JA)

        assertEquals(MealStatus.RECOGNIZING, response.status)
        assertTrue(mealFoodRepository.findAllByMealIdOrderByDisplayOrderAsc(meal.id).isEmpty())
        assertNull(mealSnapshotRepository.findByMealId(meal.id))
        assertTrue(mealFoodSnapshotRepository.findAll().none { it.mealSnapshotId == mealSnapshotId })
        val job = assertNotNull(findJob(meal.id))
        assertEquals(MealAnalysisJobCategory.RECOGNITION, job.category)
        assertEquals(Language.JA, job.language)
        assertNull(job.leaseExpiresAt)
        val savedMeal = mealRepository.findById(meal.id).orElseThrow()
        assertEquals(MealStatus.RECOGNIZING, savedMeal.status)
        assertEquals("", savedMeal.name)

        // 스냅샷은 식사당 하나다. 지웠으므로 다시 인식한 결과를 저장할 수 있다.
        mealFoodService.createMealFoods(meal.id, recognition, MealRecognitionCategory.WORKER)
        assertEquals(MealRecognitionCategory.WORKER, mealSnapshotRepository.findByMealId(meal.id)?.recognitionCategory)
    }

    @Test
    fun `재분석을 동시에 두 번 눌러도 인식 작업은 하나만 생기고 나머지는 거절된다`() {
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = "retry-twice@nyummy.co.kr")).id
        profileRepository.save(Profile(userId = userId))
        val meal = mealRepository.save(
            Meal(
                status = MealStatus.FAILED,
                imageKey = "meals/$userId/2026/11/14/retry-twice.jpg",
                mealAt = Instant.now(clock),
                userId = userId,
                analysisVersion = MealAnalysisVersion.V2,
            ),
        )

        val failures = runConcurrently(2) { mealService.retryNutritionAnalysisV2(userId, meal.id, Language.KO) }

        assertEquals(1, failures.size, "실패: $failures")
        assertEquals(MealErrorCode.ANALYSIS_NOT_RETRYABLE, (failures.single() as BusinessException).errorCode)
        assertEquals(1, mealAnalysisJobRepository.findAll().count { it.mealId == meal.id })
    }

    // 끼니 알림이 보는 최근 식사 (MealRepository.existsMealAfter)

    @Test
    fun `최근에 먹은 식사는 실패한 식사와 지운 식사만 빼고 상태와 상관없이 기록한 것으로 본다`() {
        val now = Instant.now(clock)
        val oneHourAgo = now.minusSeconds(3600)

        fun existsRecentMeal(email: String, status: MealStatus, mealAt: Instant, isDeleted: Boolean = false): Boolean {
            val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = email)).id
            mealRepository.save(
                Meal(status = status, imageKey = "meals/$userId/$email.jpg", mealAt = mealAt, userId = userId).apply {
                    if (isDeleted) updateDeletedAt(now)
                },
            )
            return mealRepository.existsMealAfter(userId, MealStatus.FAILED, oneHourAgo)
        }
        val recently = now.minusSeconds(600)

        assertTrue(existsRecentMeal("recent-recognizing@nyummy.co.kr", MealStatus.RECOGNIZING, recently))
        assertTrue(existsRecentMeal("recent-waiting@nyummy.co.kr", MealStatus.WAITING, recently))
        assertTrue(existsRecentMeal("recent-analyzing@nyummy.co.kr", MealStatus.ANALYZING, recently))
        assertTrue(existsRecentMeal("recent-completed@nyummy.co.kr", MealStatus.COMPLETED, recently))
        assertFalse(existsRecentMeal("recent-failed@nyummy.co.kr", MealStatus.FAILED, recently))
        assertFalse(existsRecentMeal("recent-deleted@nyummy.co.kr", MealStatus.COMPLETED, recently, isDeleted = true))
        assertFalse(existsRecentMeal("old-completed@nyummy.co.kr", MealStatus.COMPLETED, now.minusSeconds(7200)))
    }

    /** [count]개의 스레드가 같은 순간에 [action]을 실행하게 하고, 실패한 것의 예외를 돌려준다. */
    private fun runConcurrently(count: Int, action: (index: Int) -> Unit): List<Throwable> {
        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)
        val futures = (0 until count).map { index ->
            executor.submit<Result<Unit>> {
                startGate.await()
                runCatching { action(index) }
            }
        }
        startGate.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()

        return results.mapNotNull { it.exceptionOrNull() }
    }
}
