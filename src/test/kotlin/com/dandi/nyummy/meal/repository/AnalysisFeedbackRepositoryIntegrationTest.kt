package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.auth.enum.AuthProvider
import com.dandi.nyummy.meal.entity.AnalysisFeedback
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.user.entity.User
import com.dandi.nyummy.user.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 어드민 분석 평가 목록 쿼리의 별점·이유·커서 조건이 실제 MySQL에서 의도대로 거르는지 확인한다.
 *
 * 컨테이너를 테스트끼리 함께 쓰므로 결과는 각 테스트가 만든 평가로만 좁혀서 비교한다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AnalysisFeedbackRepositoryIntegrationTest @Autowired constructor(
    private val analysisFeedbackRepository: AnalysisFeedbackRepository,
    private val mealRepository: MealRepository,
    private val userRepository: UserRepository,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")

        private const val SIZE = 50
    }

    @Test
    fun `이유 조건은 비었거나 공백뿐인 이유를 이유 없음으로 본다`() {
        val userId = createUser()
        val written = createAnalysisFeedback(userId, reason = "양이 실제보다 적게 나왔어요")
        val absent = createAnalysisFeedback(userId, reason = null)
        val empty = createAnalysisFeedback(userId, reason = "")
        val blank = createAnalysisFeedback(userId, reason = "   ")
        val createdIds = setOf(written, absent, empty, blank)

        assertEquals(listOf(written), findAnalysisFeedbackIds(createdIds, hasReason = true))
        assertEquals(listOf(blank, empty, absent), findAnalysisFeedbackIds(createdIds, hasReason = false))
        assertEquals(listOf(blank, empty, absent, written), findAnalysisFeedbackIds(createdIds))
    }

    @Test
    fun `별점 상한은 그 별점을 포함해 이하만 남긴다`() {
        val userId = createUser()
        val low = createAnalysisFeedback(userId, rating = 1.5f)
        val boundary = createAnalysisFeedback(userId, rating = 2.0f)
        val high = createAnalysisFeedback(userId, rating = 2.5f)
        val createdIds = setOf(low, boundary, high)

        assertEquals(listOf(boundary, low), findAnalysisFeedbackIds(createdIds, maxRating = 2.0f))
    }

    @Test
    fun `커서보다 작은 id부터 최신순으로 개수만큼 준다`() {
        val userId = createUser()
        val (_, second, third) = List(3) { createAnalysisFeedback(userId) }

        val analysisFeedbacks = analysisFeedbackRepository.findAnalysisFeedbacksBeforeCursor(
            maxRating = null,
            hasReason = null,
            cursor = third,
            pageable = PageRequest.of(0, 1),
        )

        assertEquals(listOf(second), analysisFeedbacks.content.map { it.id })
        assertTrue(analysisFeedbacks.hasNext())
    }

    @Test
    fun `사용자가 지운 식사도 id로 함께 읽힌다`() {
        val userId = createUser()
        val deletedAt = Instant.parse("2026-11-14T03:00:00Z")
        val mealId = createMeal(userId, deletedAt = deletedAt)

        val meals = mealRepository.findAllById(listOf(mealId))

        assertEquals(deletedAt, meals.single().deletedAt)
    }

    private fun findAnalysisFeedbackIds(
        createdIds: Set<Long>,
        maxRating: Float? = null,
        hasReason: Boolean? = null,
    ): List<Long> = analysisFeedbackRepository
        .findAnalysisFeedbacksBeforeCursor(maxRating, hasReason, cursor = createdIds.max() + 1, PageRequest.of(0, SIZE))
        .content
        .map { it.id }
        .filter { it in createdIds }

    private fun createUser(): Long =
        userRepository.save(User(provider = AuthProvider.EMAIL, email = "${UUID.randomUUID()}@nyummy.co.kr")).id

    private fun createMeal(userId: Long, deletedAt: Instant? = null): Long = mealRepository.save(
        Meal(
            status = MealStatus.COMPLETED,
            imageKey = "meals/$userId/${UUID.randomUUID()}.jpg",
            mealAt = Instant.now(),
            userId = userId,
        ).apply { deletedAt?.let { updateDeletedAt(it) } },
    ).id

    private fun createAnalysisFeedback(userId: Long, rating: Float = 3.0f, reason: String? = "칼로리가 너무 높게 나왔어요"): Long =
        analysisFeedbackRepository.save(
            AnalysisFeedback(rating = rating, reason = reason, mealId = createMeal(userId), userId = userId),
        ).id
}
