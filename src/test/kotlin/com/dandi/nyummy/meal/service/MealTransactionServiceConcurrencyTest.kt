package com.dandi.nyummy.meal.service

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.mail.repository.MailRepository
import com.dandi.nyummy.meal.dto.RewardBoxResult
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.streak.entity.Streak
import com.dandi.nyummy.streak.repository.StreakRepository
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 같은 사용자의 식사 기록이 동시에 들어와도 하루 시도 횟수를 넘겨 저장되지 않고 연속 기록도 한 번만 반영되는지 실제 DB로 확인한다.
 * 이 경로에는 외부 호출(S3·AI)이 없어서 [MealService.createMeal]의 DB 순서(이미지 확정 전 검사 → 트랜잭션 서비스)를 그대로 따라 한다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MealTransactionServiceConcurrencyTest @Autowired constructor(
    private val mealAttemptService: MealAttemptService,
    private val mealTransactionService: MealTransactionService,
    private val userRepository: UserRepository,
    private val profileRepository: ProfileRepository,
    private val mealRepository: MealRepository,
    private val mailRepository: MailRepository,
    private val streakRepository: StreakRepository,
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

        private const val EXISTING_MEAL_COUNT = 4
        private const val REQUEST_COUNT = 10
        private val MEAL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }

    @Test
    fun `같은 사용자가 동시에 기록해도 하루 시도 횟수를 넘겨 저장하지 않는다`() {
        val userId = userRepository.save(User(provider = AuthProvider.EMAIL, email = "concurrency@nyummy.co.kr")).id
        profileRepository.save(Profile(userId = userId))
        val now = Instant.now(clock)
        repeat(EXISTING_MEAL_COUNT) { index ->
            mealRepository.save(createMeal(userId, "existing-$index", now))
        }
        // 오늘 이미 기록한 사용자라 연속 기록 행도 있다. 행이 없으면 첫 행 INSERT끼리 uk_streaks_user_id에 걸려
        // 한 건만 남으므로, 잠금이 빠져도 이 테스트가 통과해 버린다.
        streakRepository.save(Streak(userId = userId, lastMealAt = now))

        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(REQUEST_COUNT)
        val futures = (1..REQUEST_COUNT).map { index ->
            executor.submit<Result<Unit>> {
                startGate.await()
                runCatching {
                    mealAttemptService.validateDailyCount(userId)
                    mealTransactionService.createMeal(
                        createMeal(userId, "concurrent-$index", now),
                        RewardBoxResult(RewardBoxCategory.GOLD, 412),
                        now.plus(Duration.ofDays(7)),
                    )
                    Unit
                }
            }
        }
        startGate.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()

        val successCount = results.count { it.isSuccess }
        val todayAttempts = mealAttemptService.countDailyAttempts(userId, LocalDate.now(clock.withZone(MEAL_ZONE)))
        val rewardBoxMailCount = mailRepository.findAll().count { it.userId == userId }
        val summary = "성공 ${successCount}건, 오늘 식사 ${todayAttempts}건, 상자 우편 ${rewardBoxMailCount}건"

        assertEquals(6, successCount, summary)
        assertEquals(10L, todayAttempts, summary)
        assertEquals(6, rewardBoxMailCount, summary)
        assertEquals(1, streakRepository.findByUserId(userId)?.streakDays, summary)
        results.mapNotNull { it.exceptionOrNull() }.forEach { failure ->
            assertEquals(MealErrorCode.DAILY_COUNT_EXCEEDED, (failure as BusinessException).errorCode, summary)
        }
    }

    private fun createMeal(userId: Long, name: String, mealAt: Instant) = Meal(
        status = MealStatus.WAITING,
        imageKey = "meals/$userId/$name.jpg",
        mealAt = mealAt,
        userId = userId,
    )
}
