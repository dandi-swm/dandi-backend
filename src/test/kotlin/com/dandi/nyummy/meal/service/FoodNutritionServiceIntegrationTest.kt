package com.dandi.nyummy.meal.service

import com.dandi.nyummy.meal.dto.NutritionHint
import com.dandi.nyummy.meal.dto.ServingNutrition
import com.dandi.nyummy.meal.dto.ServingNutritionResult
import com.dandi.nyummy.meal.entity.FoodNutrition
import com.dandi.nyummy.meal.entity.Icon
import com.dandi.nyummy.meal.enum.NetContentUnit
import com.dandi.nyummy.meal.enum.PackageNutritionMismatchCategory
import com.dandi.nyummy.meal.repository.FoodNutritionRepository
import com.dandi.nyummy.meal.repository.IconRepository
import com.dandi.nyummy.meal.repository.PackageNutritionRepository
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
 * 제품 이름·용량별 영양은 이름과 용량마다 행 하나로 저장되고, 용량이 없는 제품도 그렇게 찾아지는지 확인한다.
 * 용량이 NULL인 행을 찾는 조회와, NULL을 0과 빈 글자로 바꿔 비교하는 유니크 제약도 mock으로 확인할 수 없다.
 *
 * 컨테이너를 테스트끼리 함께 쓰므로 테스트마다 다른 이름을 쓴다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class FoodNutritionServiceIntegrationTest @Autowired constructor(
    private val foodNutritionService: FoodNutritionService,
    private val foodNutritionRepository: FoodNutritionRepository,
    private val packageNutritionRepository: PackageNutritionRepository,
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

    // 제품 이름·용량별 영양

    private val netContent = BigDecimal("250.0")
    private val packageServingNutrition =
        ServingNutrition(BigDecimal("100.00"), BigDecimal("4.00"), BigDecimal("20.00"), BigDecimal("0.70"))
    private val otherPackageServingNutrition =
        ServingNutrition(BigDecimal("110.00"), BigDecimal("5.00"), BigDecimal("21.00"), BigDecimal("0.80"))

    private fun createPackageName() = "프로핏 ${UUID.randomUUID().toString().take(8)}"

    private fun countPackageNutritions(name: String): Int = checkNotNull(
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM package_nutritions WHERE name = ?", Int::class.java, name),
    )

    @Test
    fun `저장한 제품의 값을 같은 이름과 용량으로 찾는다`() {
        val name = createPackageName()
        val iconId = createIconId()

        foodNutritionService.createPackageNutrition(
            name,
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            iconId,
        )

        assertEquals(
            ServingNutritionResult(packageServingNutrition, iconId),
            foodNutritionService.getPackageNutrition(name, netContent, NetContentUnit.ML),
        )
    }

    @Test
    fun `소수 자리가 달라도 같은 용량으로 찾는다`() {
        val name = createPackageName()
        val iconId = createIconId()
        foodNutritionService.createPackageNutrition(
            name,
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            iconId,
        )

        val result = foodNutritionService.getPackageNutrition(name, BigDecimal("250"), NetContentUnit.ML)

        assertEquals(ServingNutritionResult(packageServingNutrition, iconId), result)
    }

    @Test
    fun `용량이나 단위가 다르면 다른 제품이라 찾지 못하고 따로 저장된다`() {
        val name = createPackageName()
        val iconId = createIconId()
        foodNutritionService.createPackageNutrition(
            name,
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            iconId,
        )

        assertNull(foodNutritionService.getPackageNutrition(name, BigDecimal("500.0"), NetContentUnit.ML))
        assertNull(foodNutritionService.getPackageNutrition(name, netContent, NetContentUnit.G))
        assertNull(foodNutritionService.getPackageNutrition(name, null, null))

        foodNutritionService.createPackageNutrition(
            name,
            BigDecimal("500.0"),
            NetContentUnit.ML,
            otherPackageServingNutrition,
            iconId,
        )

        assertEquals(2, countPackageNutritions(name))
        assertEquals(
            ServingNutritionResult(otherPackageServingNutrition, iconId),
            foodNutritionService.getPackageNutrition(name, BigDecimal("500.0"), NetContentUnit.ML),
        )
    }

    @Test
    fun `용량을 읽지 못한 제품은 용량 없이 저장하고 용량 없이 찾는다`() {
        val name = createPackageName()
        val iconId = createIconId()

        foodNutritionService.createPackageNutrition(name, null, null, packageServingNutrition, iconId)

        assertEquals(
            ServingNutritionResult(packageServingNutrition, iconId),
            foodNutritionService.getPackageNutrition(name, null, null),
        )
        assertNull(foodNutritionService.getPackageNutrition(name, netContent, NetContentUnit.ML))
    }

    @Test
    fun `같은 제품을 다시 저장해도 처음 값이 남고 행은 하나다`() {
        val name = createPackageName()
        val iconId = createIconId()
        foodNutritionService.createPackageNutrition(
            name,
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            iconId,
        )

        foodNutritionService.createPackageNutrition(
            name,
            netContent,
            NetContentUnit.ML,
            otherPackageServingNutrition,
            createIconId(),
        )

        assertEquals(1, countPackageNutritions(name))
        assertEquals(
            ServingNutritionResult(packageServingNutrition, iconId),
            foodNutritionService.getPackageNutrition(name, netContent, NetContentUnit.ML),
        )
    }

    @Test
    fun `용량이 없는 같은 이름도 다시 저장하면 처음 값이 남고 행은 하나다`() {
        // 컬럼을 그대로 묶은 유니크 제약이면 NULL이 낀 행은 중복으로 보지 않아 둘이 들어간다.
        val name = createPackageName()
        val iconId = createIconId()
        foodNutritionService.createPackageNutrition(name, null, null, packageServingNutrition, iconId)

        foodNutritionService.createPackageNutrition(name, null, null, otherPackageServingNutrition, iconId)

        assertEquals(1, countPackageNutritions(name))
        assertEquals(
            ServingNutritionResult(packageServingNutrition, iconId),
            foodNutritionService.getPackageNutrition(name, null, null),
        )
    }

    @Test
    fun `저장해 둔 값을 바꾸면 새 값으로 찾아지고 아이콘은 그대로이며 고친 시각이 옮겨진다`() {
        val name = createPackageName()
        val iconId = createIconId()
        foodNutritionService.createPackageNutrition(
            name,
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            iconId,
        )
        jdbcTemplate.update(
            "UPDATE package_nutritions SET updated_at = ? WHERE name = ?",
            Timestamp.from(Instant.now().minus(Duration.ofDays(30))),
            name,
        )

        foodNutritionService.updatePackageNutrition(name, netContent, NetContentUnit.ML, otherPackageServingNutrition)

        assertEquals(
            ServingNutritionResult(otherPackageServingNutrition, iconId),
            foodNutritionService.getPackageNutrition(name, netContent, NetContentUnit.ML),
        )
        val packageNutrition = assertNotNull(
            packageNutritionRepository.findByNameAndNetContentAndNetContentUnit(name, netContent, NetContentUnit.ML),
        )
        assertTrue(packageNutrition.updatedAt.isAfter(Instant.now().minus(Duration.ofDays(1))))
    }

    @Test
    fun `영문 제품 이름은 대소문자가 달라도 같은 제품으로 찾는다`() {
        // name 컬럼의 정렬 규칙(utf8mb4_0900_ai_ci)이 대소문자를 구분하지 않는다.
        val suffix = UUID.randomUUID().toString().take(8)
        val iconId = createIconId()
        foodNutritionService.createPackageNutrition(
            "Protein $suffix",
            netContent,
            NetContentUnit.ML,
            packageServingNutrition,
            iconId,
        )

        val result = foodNutritionService.getPackageNutrition("protein $suffix", netContent, NetContentUnit.ML)

        assertEquals(ServingNutritionResult(packageServingNutrition, iconId), result)
    }

    @Test
    fun `불일치 기록은 사진에 일부만 적힌 값과 없는 식사의 id로도 쌓이고 같은 제품에 여러 번 쌓인다`() {
        // 사진 값은 NULL을 허용하고, meal_id에는 외래 키가 없어 식사가 지워진 뒤에도 기록이 남는다.
        val name = createPackageName()

        foodNutritionService.createPackageNutritionMismatch(
            category = PackageNutritionMismatchCategory.SAVED,
            name = name,
            netContent = netContent,
            netContentUnit = NetContentUnit.ML,
            nutritionHint = NutritionHint(calory = BigDecimal("120.00")),
            servingNutrition = packageServingNutrition,
            mealId = Long.MAX_VALUE,
        )
        foodNutritionService.createPackageNutritionMismatch(
            category = PackageNutritionMismatchCategory.NAMED,
            name = name,
            netContent = null,
            netContentUnit = null,
            nutritionHint = NutritionHint(
                otherPackageServingNutrition.calory,
                otherPackageServingNutrition.carbs,
                otherPackageServingNutrition.protein,
                otherPackageServingNutrition.fat,
            ),
            servingNutrition = packageServingNutrition,
            mealId = Long.MAX_VALUE,
        )

        val mismatches = jdbcTemplate.queryForList(
            "SELECT category, net_content, nutrition_hint_calory, nutrition_hint_carbs, serving_calory " +
                "FROM package_nutrition_mismatches WHERE name = ? ORDER BY id",
            name,
        )
        assertEquals(listOf("SAVED", "NAMED"), mismatches.map { it["category"] })
        assertEquals(0, BigDecimal("250.0").compareTo(mismatches[0]["net_content"] as BigDecimal))
        assertNull(mismatches[1]["net_content"])
        assertEquals(0, BigDecimal("120.00").compareTo(mismatches[0]["nutrition_hint_calory"] as BigDecimal))
        assertNull(mismatches[0]["nutrition_hint_carbs"])
        assertEquals(0, BigDecimal("5.00").compareTo(mismatches[1]["nutrition_hint_carbs"] as BigDecimal))
        assertEquals(0, BigDecimal("100.00").compareTo(mismatches[1]["serving_calory"] as BigDecimal))
    }
}
