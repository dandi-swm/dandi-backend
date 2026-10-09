package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.infra.image.s3.S3Service
import com.dandi.nyummy.meal.calculator.calculateDailyNutritionEvaluation
import com.dandi.nyummy.meal.calculator.calculateMonthlyCalendarRange
import com.dandi.nyummy.meal.calculator.calculateRecommendedDailyIntake
import com.dandi.nyummy.meal.dto.DailyMealsResponse
import com.dandi.nyummy.meal.dto.DailyNutritionResponse
import com.dandi.nyummy.meal.dto.MealResponse
import com.dandi.nyummy.meal.dto.MealStatusResponse
import com.dandi.nyummy.meal.dto.MonthlyMealDayResponse
import com.dandi.nyummy.meal.dto.MonthlyMealsResponse
import com.dandi.nyummy.meal.dto.Nutrition
import com.dandi.nyummy.meal.dto.Streak
import com.dandi.nyummy.meal.dto.TodayMealSummary
import com.dandi.nyummy.meal.entity.Meal
import com.dandi.nyummy.meal.entity.MealOutbox
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.event.MealAnalysisRequested
import com.dandi.nyummy.meal.mapper.toDailyMealResponse
import com.dandi.nyummy.meal.mapper.toMealResponse
import com.dandi.nyummy.meal.mapper.toMealStatusResponse
import com.dandi.nyummy.meal.mapper.toNutrition
import com.dandi.nyummy.meal.queue.MealAnalysisMessage
import com.dandi.nyummy.meal.queue.MealAnalysisPublisher
import com.dandi.nyummy.meal.repository.MealOutboxRepository
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.repository.ProfileRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

@Service
class MealService(
    private val mealRepository: MealRepository,
    private val outboxRepository: MealOutboxRepository,
    private val analysisPublisher: MealAnalysisPublisher,
    private val events: ApplicationEventPublisher,
    private val s3Service: S3Service,
    private val clock: Clock,
    private val profileRepository: ProfileRepository,
) {

    /**
     * imageKey가 식사 기록에 사용된 적 있는지 확인한다. 소프트 삭제된 식사도 포함한다.
     *
     * @param imageKey 확인할 이미지 키
     * @return 사용된 적 있으면 true
     */
    @Transactional(readOnly = true)
    fun existsMealByImageKey(imageKey: String): Boolean = mealRepository.existsByImageKey(imageKey)

    /**
     * 식사와 비동기 영양 분석 요청(Outbox)을 한 트랜잭션으로 저장한다.
     *
     * 커밋 후 Worker가 상태를 바꿔도 반환값은 접수 당시 상태(WAITING)다.
     *
     * @param meal 저장할 [Meal]
     * @return 저장된 [Meal]의 분석 상태를 담은 [MealStatusResponse]
     * @throws DataIntegrityViolationException imageKey UNIQUE 제약 등 무결성 제약을 위반한 경우
     */
    @Transactional
    fun createMeal(meal: Meal): MealStatusResponse {
        mealRepository.save(meal)
        createMealOutbox(meal.id)
        return meal.toMealStatusResponse()
    }

    /**
     * 식사의 영양 분석 요청을 Outbox에 저장하고, 커밋 후 즉시 전달되도록 이벤트를 발행한다.
     *
     * 식사 상태 변경과 원자적으로 저장되어야 하므로 호출자의 트랜잭션에만 참여한다.
     *
     * @param mealId 분석을 요청할 [Meal]의 ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createMealOutbox(mealId: Long) {
        val outbox = outboxRepository.save(MealOutbox(mealId, Instant.now(clock)))
        events.publishEvent(MealAnalysisRequested(outbox.id))
    }

    /**
     * 아직 전달되지 않은 Outbox를 분석 큐에 적재하고 전달 완료로 표시한다.
     *
     * 큐 적재와 전달 완료 표시는 같은 트랜잭션에서 커밋된다. 이미 전달된 Outbox는 무시하므로
     * 즉시 전달과 복구 스케줄러가 같은 Outbox를 동시에 처리해도 한 번만 적재된다.
     *
     * @param outboxId 전달할 [MealOutbox]의 ID
     */
    @Transactional
    fun dispatchMealOutbox(outboxId: Long) {
        val outbox = outboxRepository.findByIdForUpdate(outboxId) ?: return
        if (outbox.publishedAt != null) return

        analysisPublisher.publish(MealAnalysisMessage(outbox.id, outbox.mealId))
        outbox.markPublished(Instant.now(clock))
    }

    /**
     * 특정 날짜의 식사 목록과 하루 영양 섭취 현황(현재/목표)을 조회한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param year 조회할 날짜의 연도
     * @param month 조회할 날짜의 월
     * @param day 조회할 날짜의 일
     * @return 해당 날짜의 식사 목록과 [DailyNutritionResponse]를 담은 [DailyMealsResponse]
     */
    @Transactional(readOnly = true)
    fun getDailyMeals(userId: Long, year: Int, month: Int, day: Int): DailyMealsResponse {
        // TODO: 사용자별 timezone에 맞게 계산
        val zone = ZoneId.of("Asia/Seoul")
        val date = LocalDate.of(year, month, day)
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()

        val mealsByPeriod = mealRepository.getMealsByUserIdAndPeriod(userId, start, end)

        val meals = mealsByPeriod.map { it.toDailyMealResponse() }

        val profile = profileRepository.getProfileByUserId(userId)

        val recommended = calculateRecommendedDailyIntake(profile, LocalDate.of(year, month, day))

        val dailyNutrition = DailyNutritionResponse(
            current = mealsByPeriod.fold(Nutrition.ZERO) { acc, meal -> acc + meal.toNutrition() },
            target = recommended,
        )

        return DailyMealsResponse(
            date = LocalDate.of(year, month, day),
            meals = meals,
            dailyNutrition = dailyNutrition,
        )
    }

    /**
     * 월간 캘린더 범위([calculateMonthlyCalendarRange])의 날짜별 하루 평가를 계산해 조회한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param year 조회할 연도
     * @param month 조회할 월
     * @return 캘린더 범위의 날짜별 [MonthlyMealDayResponse] 목록을 담은 [MonthlyMealsResponse]
     */
    @Transactional(readOnly = true)
    fun getMonthlyMeals(userId: Long, year: Int, month: Int): MonthlyMealsResponse {
        val zone = ZoneId.of("Asia/Seoul")
        val (startDate, endDate) = calculateMonthlyCalendarRange(YearMonth.of(year, month))

        val mealsByPeriod = mealRepository.getMealsByUserIdAndPeriod(
            userId,
            startDate.atStartOfDay(zone).toInstant(),
            endDate.plusDays(1).atStartOfDay(zone).toInstant(),
        )

        val mealsByDate: Map<LocalDate, List<Meal>> =
            mealsByPeriod
                .groupBy { it.mealAt.atZone(zone).toLocalDate() }

        val profile = profileRepository.getProfileByUserId(userId)
        val recommended = calculateRecommendedDailyIntake(profile, LocalDate.now())

        val days = mutableListOf<MonthlyMealDayResponse>()
        var date = startDate
        while (date <= endDate) {
            days.add(
                MonthlyMealDayResponse(
                    date = date,
                    isCurrentMonth = date.year == year && date.monthValue == month,
                    dailyNutritionEvaluation = calculateDailyNutritionEvaluation(
                        meals = mealsByDate[date] ?: emptyList(),
                        recommended = recommended,
                    ),
                    foodIconIds = pickFoodIconIds(mealsByDate[date] ?: emptyList()),
                ),
            )
            date = date.plusDays(1)
        }

        return MonthlyMealsResponse(
            year = year,
            month = month,
            days = days,
        )
    }

    private fun pickFoodIconIds(meals: List<Meal>): List<Long> {
        val iconIdsByMealAt = meals.sortedBy { it.mealAt }.map { it.iconId }
        if (iconIdsByMealAt.size <= 2) {
            return iconIdsByMealAt
        }

        val distinctIconIds = iconIdsByMealAt.distinct()

        if (distinctIconIds.size < 2) {
            return iconIdsByMealAt.take(2)
        }

        val pickedIconIds = distinctIconIds.shuffled().take(2).toSet()
        return distinctIconIds.filter { it in pickedIconIds }
    }

    /**
     * 식사 단건을 조회하고 이미지 presigned URL을 발급해 반환한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param mealId 조회할 식사 ID
     * @return 식사 정보와 이미지 URL을 담은 [MealResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun getMeal(userId: Long, mealId: Long): MealResponse {
        val meal = mealRepository.getMealByIdAndDeletedAtIsNull(mealId)
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        if (meal.userId != userId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes)

        return meal.toMealResponse(imageUrl)
    }

    /**
     * 식사 이름을 수정한다.
     *
     * @param userId 수정하는 사용자 ID
     * @param mealId 수정할 식사 ID
     * @param name 변경할 식사 이름
     * @return 수정된 식사 정보와 이미지 URL을 담은 [MealResponse]
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional
    fun updateMeal(userId: Long, mealId: Long, name: String): MealResponse {
        val meal = mealRepository.findByIdForUpdate(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        if (meal.userId != userId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }

        val imageUrl = s3Service.createPresignedGetUrl(meal.imageKey, 10.minutes)

        meal.updateName(name)

        return meal.toMealResponse(imageUrl)
    }

    /**
     * 식사 기록을 소프트 삭제한다(deletedAt 기록).
     *
     * @param userId 삭제하는 사용자 ID
     * @param mealId 삭제할 식사 ID
     * @throws BusinessException [MealErrorCode.MEAL_NOT_FOUND] mealId에 해당하는 식사가 없거나, 삭제된 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] mealId에 해당하는 userId가 아닌 경우
     */
    @Transactional
    fun deleteMeal(userId: Long, mealId: Long) {
        val meal = mealRepository.findByIdForUpdate(mealId)
            ?.takeIf { it.deletedAt == null }
            ?: throw BusinessException(MealErrorCode.MEAL_NOT_FOUND)

        if (meal.userId != userId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }

        meal.updateDeletedAt(Instant.now(clock))
    }

    @Transactional(readOnly = true)
    fun getStreak(userId: Long): Streak {
        // TODO: 스트릭 구현하기
        return Streak(0, 0)
    }

    /**
     * 오늘의 식사 현황을 조회한다.
     *
     * 끼니 수는 분석 상태와 무관하게 세고, 섭취 칼로리는 분석이 끝난 식사만 더한다.
     * 분석 중인 식사도 사용자가 이미 기록한 끼니이므로 개수에서 빠지면 어색하지만,
     * 칼로리는 아직 값이 없어 더할 것이 없기 때문이다.
     *
     * 목표 칼로리는 프로필에서 계산하며, 프로필이 비어 있으면 기본 권장량이 쓰인다.
     *
     * @param userId 조회하는 사용자 ID
     * @return 오늘 기록한 끼니 수와 섭취·목표 칼로리를 담은 [TodayMealSummary]
     */
    @Transactional(readOnly = true)
    fun getTodayMealSummary(userId: Long): TodayMealSummary {
        // TODO: 사용자별 timezone에 맞게 계산
        val zone = ZoneId.of("Asia/Seoul")
        val today = Instant.now(clock).atZone(zone).toLocalDate()

        // 하루 경계는 [오늘 0시, 내일 0시)로 잡는다. 지금 시각까지만 조회하면
        // 기기 시계가 앞서 미래 시각으로 기록된 식사가 집계에서 빠진다.
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()

        val meals = mealRepository.getMealsByUserIdAndPeriod(userId, start, end)

        // 분석 전·실패한 식사는 calory가 null이므로 집계에서 제외한다.
        val currentCalory = meals
            .filter { it.status == MealStatus.COMPLETED }
            .sumOf { it.calory ?: 0 }

        val profile = profileRepository.getProfileByUserId(userId)
        val targetCalory = calculateRecommendedDailyIntake(profile, today).calory

        return TodayMealSummary(
            todayRecordedCount = meals.size,
            todayCurrentCalory = currentCalory,
            todayTargetCalory = targetCalory,
        )
    }
}
