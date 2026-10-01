package com.dandi.nyummy.home.service

import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.home.dto.HomeResponse
import com.dandi.nyummy.home.mapper.toHomeUser
import com.dandi.nyummy.meal.service.MealService
import com.dandi.nyummy.user.repository.ProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HomeService(private val mealService: MealService, private val profileRepository: ProfileRepository) {
    companion object {
        val log = LoggerFactory.getLogger(HomeService::class.java)
    }

    /**
     * 홈 화면에 필요한 값을 도메인별로 모아 반환한다.
     *
     * 조합만 하고 계산은 각 도메인 서비스에 맡긴다. 여기에 규칙을 넣으면 같은 규칙이
     * 다른 화면에서 필요해질 때 복사되고, 두 벌이 어긋나기 시작한다.
     *
     * 한 번의 요청으로 묶는 이유는 값들이 서로 맞물려 있기 때문이다. 따로 조회하면
     * 그 사이에 기록이 바뀌어 화면 안에서 어긋난 값이 보일 수 있다.
     *
     * @param userId 조회하는 사용자 ID
     * @return 코인·연속 기록·오늘 식사 현황을 담은 [HomeResponse]
     * @throws BusinessException [UserErrorCode.PROFILE_NOT_FOUND] 사용자 프로필이 없는 경우
     */
    @Transactional(readOnly = true)
    fun getHome(userId: Long): HomeResponse {
        // 프로필은 가입 트랜잭션에서 함께 생성되므로 없을 수 없다.
        // 없다면 요청이 잘못된 게 아니라 데이터가 깨진 것이므로, 추적할 수 있게 userId를 남긴다.
        val profile = profileRepository.getProfileByUserId(userId)
            ?: run {
                log.error("가입 시 생성되어야 할 프로필이 없습니다: userId={}", userId)
                throw BusinessException(UserErrorCode.PROFILE_NOT_FOUND)
            }
        val streak = mealService.getStreak(userId)
        val todayMealSummary = mealService.getTodayMealSummary(userId)

        val response = HomeResponse(
            user = profile.toHomeUser(),
            streak = streak,
            todayMealSummary = todayMealSummary,
        )

        return response
    }
}
