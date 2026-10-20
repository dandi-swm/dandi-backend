package com.dandi.nyummy.home.service

import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.home.dto.HomeResponse
import com.dandi.nyummy.inquiry.service.InquiryService
import com.dandi.nyummy.mail.service.MailService
import com.dandi.nyummy.meal.service.MealService
import com.dandi.nyummy.room.service.RoomService
import com.dandi.nyummy.user.dto.HomeUserResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class HomeService(
    private val mealService: MealService,
    private val inquiryService: InquiryService,
    private val mailService: MailService,
    private val roomService: RoomService,
    private val coinService: CoinService,
) {
    /**
     * 홈 화면에 필요한 값을 도메인별로 모아 반환한다.
     *
     * 조합만 하고 계산은 각 도메인 서비스에 맡긴다. 여기에 규칙을 넣으면 같은 규칙이
     * 다른 화면에서 필요해질 때 복사되고, 두 벌이 어긋나기 시작한다.
     *
     * 한 번의 요청으로 묶는 이유는 값들이 서로 맞물려 있기 때문이다. 따로 조회하면
     * 그 사이에 기록이 바뀌어 화면 안에서 어긋난 값이 보일 수 있다.
     *
     * 우편함 배지는 받을 수 있는 우편이나 안 읽은 문의 답변 중 하나라도 있으면 켠다. 두 도메인을 묶는 규칙이라
     * 어느 한 도메인 서비스에 둘 수 없어 여기서 합친다. 우편을 먼저 확인하고, 있으면 문의는 조회하지 않는다.
     *
     * @param userId 조회하는 사용자 ID
     * @return 코인·연속 기록·오늘 식사 현황·우편함 배지 여부·마이룸 장착 아이템을 담은 [HomeResponse]
     */
    @Transactional(readOnly = true)
    fun getHome(userId: Long): HomeResponse {
        val coin = coinService.getBalance(userId)
        val streak = mealService.getStreak(userId)
        val todayMealSummary = mealService.getTodayMealSummary(userId)
        val hasMailboxBadge = mailService.existsNotClaimedMail(userId) || inquiryService.existsUnreadAnswer(userId)
        val equippedRoomItems = roomService.getEquippedRoomItems(userId)

        val response = HomeResponse(
            user = HomeUserResponse(coin = coin),
            streak = streak,
            todayMealSummary = todayMealSummary,
            hasMailboxBadge = hasMailboxBadge,
            equippedRoomItems = equippedRoomItems,
        )

        return response
    }
}
