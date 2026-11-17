package com.dandi.nyummy.meal.service

import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.FoodNutrition
import com.dandi.nyummy.meal.entity.Icon
import com.dandi.nyummy.meal.repository.FoodNutritionRepository
import com.dandi.nyummy.meal.repository.IconRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 음식 이름별 1인분 영양이 실제 MySQL에서 이름 하나에 행 하나로 저장되고, 유효 기간이 지난 값만 바뀌는지 확인한다.
 * 트랜잭션 없이 리포지토리 호출마다 커밋하는 저장과, 값이 같아도 값을 구한 시각을 옮기는 갱신은 mock으로 확인할 수 없다.
 *
 * 컨테이너를 테스트끼리 함께 쓰므로 테스트마다 다른 이름을 쓴다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class FoodNutritionServiceIntegrationTest @Autowired constructor(
    private val foodNutritionService: FoodNutritionService,
    private val foodNutritionRepository: FoodNutritionRepository,
    private val iconRepository: IconRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.4.9")
            .withDatabaseName("nyummy_test")
            .withUsername("test")
            .withPassword("test")
    }

    // 15×4 + 15×4 + 20×9 = 300
    private val servingNutrition =
        ServingNutrition(BigDecimal("300.00"), BigDecimal("15.00"), BigDecimal("15.00"), BigDecimal("20.00"))

    // 15×4 + 15×4 + 14×9 = 246
    private val otherServingNutrition =
        ServingNutrition(BigDecimal("250.00"), BigDecimal("15.00"), BigDecimal("15.00"), BigDecimal("14.00"))

    private fun createName() = "찌개 ${UUID.randomUUID().toString().take(8)}"

    private fun createIconId(): Long = iconRepository.save(Icon(name = "국 ${UUID.randomUUID()}", imageUrl = "")).id

    /** 저장된 값을 구한 시각을 [duration] 전으로 돌린다. */
    private fun updateUpdatedAt(name: String, duration: Duration) {
        jdbcTemplate.update(
            "UPDATE food_nutritions SET updated_at = ? WHERE name = ?",
            Timestamp.from(Instant.now().minus(duration)),
            name,
        )
    }

    @Test
    fun `저장한 값을 같은 이름으로 찾는다`() {
        val name = createName()
        val iconId = createIconId()

        foodNutritionService.createFoodNutrition(name, ServingNutritionResult(servingNutrition, iconId))

        assertEquals(ServingNutritionResult(servingNutrition, iconId), foodNutritionService.getFoodNutrition(name))
    }

    @Test
    fun `같은 이름을 다시 저장해도 유효 기간 안이면 처음 값이 남는다`() {
        val name = createName()
        val iconId = createIconId()
        foodNutritionService.createFoodNutrition(name, ServingNutritionResult(servingNutrition, iconId))

        foodNutritionService.createFoodNutrition(name, ServingNutritionResult(otherServingNutrition, createIconId()))

        assertEquals(ServingNutritionResult(servingNutrition, iconId), foodNutritionService.getFoodNutrition(name))
    }

    @Test
    fun `유효 기간이 지난 값은 찾지 못하고 다시 저장하면 새 값으로 바뀐다`() {
        val name = createName()
        foodNutritionService.createFoodNutrition(name, ServingNutritionResult(servingNutrition, createIconId()))
        updateUpdatedAt(name, Duration.ofDays(8))
        assertNull(foodNutritionService.getFoodNutrition(name))
        val otherIconId = createIconId()

        foodNutritionService.createFoodNutrition(name, ServingNutritionResult(otherServingNutrition, otherIconId))

        assertEquals(
            ServingNutritionResult(otherServingNutrition, otherIconId),
            foodNutritionService.getFoodNutrition(name),
        )
    }

    @Test
    fun `유효 기간이 지난 값과 같은 값을 다시 저장해도 값을 구한 시각이 옮겨져 다시 찾는다`() {
        val name = createName()
        val iconId = createIconId()
        foodNutritionService.createFoodNutrition(name, ServingNutritionResult(servingNutrition, iconId))
        updateUpdatedAt(name, Duration.ofDays(8))

        foodNutritionService.createFoodNutrition(name, ServingNutritionResult(servingNutrition, iconId))

        assertEquals(ServingNutritionResult(servingNutrition, iconId), foodNutritionService.getFoodNutrition(name))
        val updatedAt = assertNotNull(foodNutritionRepository.findByName(name)).updatedAt
        assertTrue(updatedAt.isAfter(Instant.now().minus(Duration.ofDays(1))))
    }

    @Test
    fun `영문 이름은 대소문자가 달라도 같은 이름으로 찾는다`() {
        // name 컬럼의 정렬 규칙(utf8mb4_0900_ai_ci)이 대소문자를 구분하지 않는다.
        val suffix = UUID.randomUUID().toString().take(8)
        val iconId = createIconId()
        foodNutritionService.createFoodNutrition("Latte $suffix", ServingNutritionResult(servingNutrition, iconId))

        val result = foodNutritionService.getFoodNutrition("latte $suffix")

        assertEquals(ServingNutritionResult(servingNutrition, iconId), result)
    }

    @Test
    fun `같은 이름의 행은 둘이 될 수 없다`() {
        val name = createName()
        val iconId = createIconId()
        foodNutritionService.createFoodNutrition(name, ServingNutritionResult(servingNutrition, iconId))

        assertFailsWith<DataIntegrityViolationException> {
            foodNutritionRepository.save(
                FoodNutrition(
                    name = name,
                    servingCalory = servingNutrition.calory,
                    servingCarbs = servingNutrition.carbs,
                    servingProtein = servingNutrition.protein,
                    servingFat = servingNutrition.fat,
                    iconId = iconId,
                ),
            )
        }
    }
}
