package com.dandi.nyummy.meal.service

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.MealErrorCode
import com.dandi.nyummy.image.dto.UploadedImage
import com.dandi.nyummy.meal.config.MealProperties
import com.dandi.nyummy.meal.repository.MealRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 하루 단위 식사 기록 정책(하루 시도 횟수 제한, 오늘 촬영분만 허용)을 담당한다.
 *
 * 업로드 URL 발급 전 검증([com.dandi.nyummy.meal.validator.MealImageUploadValidator])과
 * 식사 생성([MealService])이 같은 규칙을 써야 하므로 별도 서비스로 둔다.
 * MealService에 두면 MealService → ImageService → 검증기 → MealService 순환이 생긴다.
 */
@Service
class MealAttemptService(
    private val mealRepository: MealRepository,
    private val mealProperties: MealProperties,
    private val clock: Clock,
) {
    companion object {
        // TODO: 사용자별 timezone에 맞게 계산
        private val MEAL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val logger = LoggerFactory.getLogger(MealAttemptService::class.java)
    }

    /**
     * 그 날짜에 기록을 시도한 횟수. 삭제한 식사와 분석 실패(FAILED)도 센다.
     *
     * "하루에 N개 보유"가 아니라 "하루에 N번 시도"가 정책이므로 지웠다고 자리가 돌아오지 않는다.
     * 레포의 다른 쿼리와 달리 `deletedAt` 조건이 없는 이유다.
     *
     * 제한 검사([validateDailyCount])와 홈 응답이 이 함수를 공유한다. 각자 세면 한쪽만 조건이
     * 바뀌어도 화면에 보이는 숫자와 실제 제한이 어긋난다.
     */
    fun countDailyAttempts(userId: Long, date: LocalDate): Long = mealRepository.countMealsByUserIdAndPeriod(
        userId,
        date.atStartOfDay(MEAL_ZONE).toInstant(),
        date.plusDays(1).atStartOfDay(MEAL_ZONE).toInstant(),
    )

    /**
     * 오늘의 기록 시도 횟수가 상한([MealProperties.maxDailyCount])에 닿았는지 검사한다.
     *
     * @param userId 기록을 시도하는 사용자 ID
     * @throws BusinessException [MealErrorCode.DAILY_COUNT_EXCEEDED] 상한에 닿은 경우
     */
    fun validateDailyCount(userId: Long) {
        val today = LocalDate.now(clock.withZone(MEAL_ZONE))

        if (countDailyAttempts(userId, today) >= mealProperties.maxDailyCount) {
            throw BusinessException(MealErrorCode.DAILY_COUNT_EXCEEDED)
        }
    }

    /**
     * EXIF 촬영 시각이 오늘(식사 기준 타임존)인지 검증한다. 촬영 시각이 없으면 통과시킨다.
     *
     * [com.dandi.nyummy.image.service.ImageService.confirmUpload]의 콜백으로 실행되므로,
     * 거부되면 이미지는 확정되지 않고 `status=temp`로 남는다.
     *
     * @param userId 식사를 등록하는 사용자 ID (로그용)
     * @param uploadedImage 공통 검증을 통과한 업로드 이미지
     * @throws BusinessException [MealErrorCode.STALE_IMAGE] 촬영 날짜가 오늘이 아닌 경우
     */
    fun validateCapturedToday(userId: Long, uploadedImage: UploadedImage) {
        val capturedAt = uploadedImage.capturedAt ?: return
        val now = Instant.now(clock)

        if (capturedAt.atZone(MEAL_ZONE).toLocalDate() != now.atZone(MEAL_ZONE).toLocalDate()) {
            logger.info(
                "촬영 날짜가 오늘이 아니라 등록 거부: userId={}, imageKey={}, capturedAt={}, now={}",
                userId,
                uploadedImage.imageKey,
                capturedAt,
                now,
            )
            throw BusinessException(MealErrorCode.STALE_IMAGE)
        }
    }
}
