package com.dandi.nyummy.cat.service

import com.dandi.nyummy.cat.calculator.calculateWeightStep
import com.dandi.nyummy.cat.calculator.isWeightUpdateDue
import com.dandi.nyummy.cat.config.CatProperties
import com.dandi.nyummy.cat.dto.CatResponse
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.mapper.toCatResponse
import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.meal.calculator.calculateRecommendedDailyIntake
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.profile.repository.ProfileRepository
import com.dandi.nyummy.security.jwt.TokenService
import com.dandi.nyummy.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalQueries.zone
import kotlin.time.Duration.Companion.days

@Service
class CatService(
    private val catRepository: CatRepository,
    private val mealRepository: MealRepository,
    private val profileRepository: ProfileRepository,
    private val catProperties: CatProperties,
    private val userRepository: UserRepository,
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CatService::class.java)
    }

    /**
     * 고양이의 현재 체형을 조회한다. 체형 평가는 하지 않으므로, 평가까지 필요하면 [updateCatWeight]를 사용한다.
     *
     * @param userId 조회하는 사용자 ID
     * @param catId 조회할 고양이 ID
     * @return 체형 단계와 표시 이름을 담은 [CatResponse]
     * @throws BusinessException [CatErrorCode.CAT_NOT_FOUND] catId에 해당하는 고양이가 없는 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 고양이가 요청자 소유가 아닌 경우
     */
    @Transactional(readOnly = true)
    fun getCatWeight(userId: Long, catId: Long): CatResponse {
        val cat = catRepository.findByIdOrNull(catId)
            ?: throw BusinessException(CatErrorCode.CAT_NOT_FOUND)

        if (cat.userId != userId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }

        return cat.toCatResponse()
    }

    /**
     * 평가 주기가 지났으면 직전 구간의 섭취 칼로리로 체형을 한 단계 갱신하고 현재 체형을 반환한다.
     * 주기가 지나지 않았으면 아무것도 쓰지 않고 저장된 체형을 반환한다.
     *
     * **의도적으로 직전 한 구간만 평가한다.** 한 달 만에 접속해도 그동안의 미기록 구간은 누적되지 않고
     * 버려진다. 고양이가 최저 체형에 고정되고 되돌리려면 과식을 반복해야 하는 상황이 게임 목적에
     * 맞지 않기 때문이다.
     *
     * 기록이 없거나 분석에 실패한 구간은 섭취량 0으로 취급되어 체형이 한 단계 내려간다.
     *
     * 동시성 제어는 하지 않는다. 병렬 호출 시 두 단계 변할 수 있어, intro API로 트리거를 옮긴 뒤
     * 낙관적 락을 검토한다.
     *
     * @param userId 요청한 사용자 ID
     * @param catId 갱신할 고양이 ID
     * @return 체형 단계와 표시 이름을 담은 [CatResponse]
     * @throws BusinessException [CatErrorCode.CAT_NOT_FOUND] catId에 해당하는 고양이가 없는 경우
     * @throws BusinessException [AuthErrorCode.FORBIDDEN] 고양이가 요청자 소유가 아닌 경우
     */
    @Transactional
    fun updateCatWeight(userId: Long, catId: Long): CatResponse {
        val cat = catRepository.findByIdOrNull(catId)
            ?: throw BusinessException(CatErrorCode.CAT_NOT_FOUND)

        if (cat.userId != userId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }

        // TODO: 사용자별 timezone에 맞게 계산
        val zone = ZoneId.of("Asia/Seoul")
        val today = Instant.now().atZone(zone).toLocalDate()
        val intervalDays = catProperties.weightUpdateIntervalDays

        if (!isWeightUpdateDue(cat.weightUpdatedAt, today, zone, intervalDays)) {
            return cat.toCatResponse()
        }

        // 아직 끝나지 않은 오늘은 제외한다. 직전 평가 구간과 맞물려 중복도 간극도 없다.
        val start = today.minusDays(intervalDays.toLong()).atStartOfDay(zone).toInstant()
        val end = today.atStartOfDay(zone).toInstant()

        val totalCalory = mealRepository.getMealsByUserIdAndPeriod(userId, start, end)
            .filter { it.status == MealStatus.COMPLETED }
            .sumOf { it.calory ?: 0 }

        val profile = profileRepository.getProfileByUserId(userId)
        val targetCalory = calculateRecommendedDailyIntake(profile, today).calory * intervalDays

        val step = calculateWeightStep(totalCalory, targetCalory, catProperties.weightUpdateTolerance)
        // 평가 시각을 호출 시각이 아니라 구간 종료 시각(end)으로 저장해 다음 평가 경계가 밀리지 않게 한다.
        cat.updateWeight(step, end)

        logger.info("고양이 체형 변화: userId = {}, step = {}, weight = {}", userId, step, cat.weight)

        return cat.toCatResponse()
    }
}
