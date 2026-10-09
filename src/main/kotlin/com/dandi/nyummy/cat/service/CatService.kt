package com.dandi.nyummy.cat.service

import com.dandi.nyummy.cat.calculator.calculateElapsedIntervals
import com.dandi.nyummy.cat.calculator.calculateWeightStep
import com.dandi.nyummy.cat.config.CatProperties
import com.dandi.nyummy.cat.dto.CatAnimationResponse
import com.dandi.nyummy.cat.dto.CatResponse
import com.dandi.nyummy.cat.dto.CreateCatRequest
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.enum.CatWeight
import com.dandi.nyummy.cat.mapper.toCat
import com.dandi.nyummy.cat.mapper.toCatResponse
import com.dandi.nyummy.cat.repository.CatAnimationLoader
import com.dandi.nyummy.cat.repository.CatRepository
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.meal.calculator.calculateRecommendedDailyIntake
import com.dandi.nyummy.meal.enum.MealStatus
import com.dandi.nyummy.meal.repository.MealRepository
import com.dandi.nyummy.user.repository.ProfileRepository
import com.dandi.nyummy.user.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.min

@Service
class CatService(
    private val catRepository: CatRepository,
    private val mealRepository: MealRepository,
    private val profileRepository: ProfileRepository,
    private val catProperties: CatProperties,
    private val clock: Clock,
    private val catAnimationLoader: CatAnimationLoader,
) {

    companion object {
        private val MAX_EVALUATED_INTERVALS = CatWeight.MAX_WEIGHT - CatWeight.MIN_WEIGHT
        private val logger = LoggerFactory.getLogger(CatService::class.java)
    }

    /**
     * 사용자의 고양이를 생성하고 온보딩에서 받은 끼니 시각을 프로필에 저장한다.
     * 사용자당 한 마리만 둔다(`cat.user_id` UNIQUE).
     *
     * 미리 검사하고도 insert를 try로 감싸는 이유는 검사와 insert 사이에 다른 요청이 끼어들 수 있기
     * 때문이다. 더블탭이나 네트워크 재시도로 동시에 들어오면 양쪽 다 검사를 통과하고 두 번째 insert가
     * 유니크 제약을 때린다. 그 경우도 사용자에게 알릴 결과는 "이미 있다"와 같다.
     *
     * @throws BusinessException [CatErrorCode.CAT_ALREADY_EXISTS] 이미 고양이가 있거나 동시 생성에서 밀린 경우
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 프로필이 없는 경우
     */
    @Transactional
    fun createCat(userId: Long, request: CreateCatRequest) {
        if (catRepository.existsByUserId(userId)) {
            logger.warn("이미 존재하는 고양이입니다: $userId")
            throw BusinessException(CatErrorCode.CAT_ALREADY_EXISTS)
        }

        val cat = request.toCat(userId)

        val profile = profileRepository.getProfileByUserId(userId)
            ?: run {
                logger.error("가입 시 생성되어야 할 프로필이 없습니다: userId={}", userId)
                throw BusinessException(UserErrorCode.PROFILE_NOT_FOUND)
            }

        profile.updateMealTime(request.breakfastHour, request.lunchHour, request.dinnerHour)

        try {
            catRepository.save(cat)
        } catch (e: DataIntegrityViolationException) {
            logger.warn("고양이 동시 생성으로 유니크 제약과 충돌했습니다: userId={}", userId, e)
            throw BusinessException(CatErrorCode.CAT_ALREADY_EXISTS)
        }
    }

    /**
     * 사용자의 고양이 정보를 조회한다. [getCatWithWeightUpdate]가 평가 주기를 보고 체형을 먼저 갱신한다.
     *
     * 고양이는 사용자당 하나이고 userId로 조회하므로, 다른 사용자의 고양이가 조회될 수 없다.
     *
     * @param userId 조회하는 사용자 ID
     * @return 이름·체형·애정도·경험치를 담은 [CatResponse]
     * @throws BusinessException [CatErrorCode.CAT_NOT_FOUND] 사용자의 고양이가 없는 경우
     */
    @Transactional
    fun getCat(userId: Long): CatResponse {
        val cat = getCatWithWeightUpdate(userId)

        return cat.toCatResponse()
    }

    /**
     * 마지막으로 밥을 먹은 시각을 갱신한다. 분석이 COMPLETED로 확정된 뒤 호출된다.
     *
     * 별도 빈의 트랜잭션으로 즉시 커밋한다.
     *
     * @param userId 밥을 먹은 사용자 ID
     * @param lastMealAt 밥을 먹은 시각
     * @throws BusinessException [CatErrorCode.CAT_NOT_FOUND] 사용자의 고양이가 없는 경우
     */
    @Transactional
    fun updateLastMealAt(userId: Long, lastMealAt: Instant) {
        val cat = catRepository.findByUserId(userId)
            ?: throw BusinessException(CatErrorCode.CAT_NOT_FOUND)

        cat.updateLastMealAt(lastMealAt)
    }

    /**
     * 고양이의 현재 체형에 해당하는 애니메이션 메타데이터를 조회한다.
     *
     * 메타데이터 자체는 S3에 있고 체형 5종뿐이므로 [CatAnimationLoader]가 캐시한다.
     *
     * @param userId 조회하는 사용자 ID
     * @return 체형에 해당하는 [CatAnimationResponse]
     * @throws BusinessException [CatErrorCode.CAT_NOT_FOUND] 사용자의 고양이가 없는 경우
     * @throws BusinessException [CatErrorCode.ANIMATION_METADATA_INVALID] 메타데이터를 읽을 수 없는 경우
     */
    @Transactional
    fun getCatAnimations(userId: Long): CatAnimationResponse {
        val cat = getCatWithWeightUpdate(userId)

        // 저장된 체형 값(-2~2)을 CatWeight로 바꿔 S3의 체형별 메타데이터를 찾는다.
        return catAnimationLoader.load(CatWeight.fromWeight(cat.weight))
    }

    /**
     * 체형이 최신인 고양이를 가져온다. 마지막 평가 이후 지난 구간을 **모두** 평가해,
     * 구간마다 한 단계씩 누적 적용한다. 3일 굶으면 한 단계, 6일이면 두 단계 내려간다.
     *
     * 체형을 읽는 모든 경로가 이걸 거친다. 그래서 `/cats`와 `/cats/animations`를 어떤 순서로
     * 부르든, 병렬로 부르든 같은 체형을 본다.
     *
     * 한 번에 평가하는 구간은 [MAX_EVALUATED_INTERVALS]개로 제한한다. 그보다 오래된 미기록
     * 구간은 버려진다 — 체형 폭이 4단계라 결과를 바꾸지 못하고, 창을 열어두면 한 해 만에 접속한
     * 사용자의 식사를 전부 읽게 된다.
     *
     * 구간은 시간 순서대로 하나씩 적용한다. 합산 후 한 번만 clamp하면 바닥(LEAN)에 닿은 뒤의
     * 굶주림이 나중 구간의 과식으로 상쇄돼 되살아난다.
     *
     * 기록이 없거나 분석에 실패한 구간은 섭취량 0으로 취급되어 체형이 한 단계 내려간다.
     * 목표 섭취량은 모든 구간에 현재 프로필 기준값을 쓴다 — 과거 구간의 나이·체중을 되살리지 않는다.
     *
     * 동시성 제어는 하지 않는다. `/cats`와 `/cats/animations`가 병렬로 들어오면 두 번 적용될 수
     * 있다. 낙관적 락은 별도로 검토한다.
     *
     * private이라 프록시를 거치지 않고 호출자의 트랜잭션에서 실행된다.
     *
     * @param userId 조회하는 사용자 ID
     * @throws BusinessException [CatErrorCode.CAT_NOT_FOUND] 사용자의 고양이가 없는 경우
     */
    private fun getCatWithWeightUpdate(userId: Long): Cat {
        val cat = catRepository.findByUserId(userId)
            ?: throw BusinessException(CatErrorCode.CAT_NOT_FOUND)

        // TODO: 사용자별 timezone에 맞게 계산
        val zone = ZoneId.of("Asia/Seoul")
        val today = Instant.now(clock).atZone(zone).toLocalDate()
        val intervalDays = catProperties.weightUpdateIntervalDays

        val lastEvaluatedDate = cat.weightUpdatedAt.atZone(zone).toLocalDate()
        val elapsedIntervals = calculateElapsedIntervals(lastEvaluatedDate, today, intervalDays)

        if (elapsedIntervals <= 0) {
            return cat
        }

        val windowEndDate = lastEvaluatedDate.plusDays(elapsedIntervals.toLong() * intervalDays)

        val intervalCount = min(elapsedIntervals, MAX_EVALUATED_INTERVALS)
        val windowStartDate = windowEndDate.minusDays(intervalCount.toLong() * intervalDays)

        val caloryByInterval = IntArray(intervalCount)
        mealRepository
            .getMealsByUserIdAndPeriod(
                userId,
                windowStartDate.atStartOfDay(zone).toInstant(),
                windowEndDate.atStartOfDay(zone).toInstant(),
            )
            .filter { it.status == MealStatus.COMPLETED }
            .forEach { meal ->
                val mealDate = meal.mealAt.atZone(zone).toLocalDate()
                val index = (ChronoUnit.DAYS.between(windowStartDate, mealDate) / intervalDays).toInt()

                caloryByInterval[index] += meal.calory ?: 0
            }

        val profile = profileRepository.getProfileByUserId(userId)
        val targetCalory = calculateRecommendedDailyIntake(profile, today).calory * intervalDays

        for (index in 0 until intervalCount) {
            // cat.weight를 루프 안에서 읽는다. 앞 구간의 결과가 반영된 값이어야 한 칸씩 수렴한다.
            val step = calculateWeightStep(
                caloryByInterval[index],
                targetCalory,
                catProperties.weightUpdateTolerance,
                cat.weight,
            )
            val intervalEndDate = windowStartDate.plusDays((index + 1).toLong() * intervalDays)

            cat.updateWeightByStep(step, intervalEndDate.atStartOfDay(zone).toInstant())

            logger.info(
                "고양이 체형 변화: userId = {}, interval = {}/{}, step = {}, weight = {}",
                userId,
                index + 1,
                intervalCount,
                step,
                cat.weight,
            )
        }

        return cat
    }
}
