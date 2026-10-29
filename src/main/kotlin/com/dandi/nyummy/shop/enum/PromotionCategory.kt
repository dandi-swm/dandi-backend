package com.dandi.nyummy.shop.enum

import java.time.Duration
import java.time.Instant

/**
 * 프로모션의 대상. 사용자가 대상인지 판정하는 기준값(가입 후 일수 등)은 설정에 둔다.
 *
 * 인테리어 탭의 분류 칩 [ShopCategory], 아이템 분류 [com.dandi.nyummy.room.enum.RoomItemCategory]와 다른 enum이다.
 */
enum class PromotionCategory {
    /** 가입한 지 얼마 안 된 사용자(신입 집사 환영) */
    NEW_USER,

    /** 아직 한 번도 사지 않은 사용자(첫 구매 혜택) */
    FIRST_PURCHASE,

    /** 한동안 접속하지 않다가 돌아온 사용자(복귀 유저 혜택) */
    RETURNING_USER,

    /** 모든 사용자, 하루 단위로 상품이 바뀐다(오늘의 할인) */
    DAILY,

    /** 모든 사용자 */
    GENERAL,
    ;

    /**
     * 사용자가 이 대상에 드는지. 진행 중인지는 따로 본다.
     *
     * [FIRST_PURCHASE]는 구매를 아직 저장하지 않아 모든 사용자, [RETURNING_USER]는 복귀 판정 기준이 없어 아무도 들지 않는다.
     *
     * @param userCreatedAt 사용자의 가입 시각
     * @param newUserTimeToLive 가입 후 이 기간 동안 [NEW_USER]다. 가입 시각 + 기간인 순간부터는 아니다
     */
    fun isEligible(userCreatedAt: Instant, now: Instant, newUserTimeToLive: Duration): Boolean = when (this) {
        NEW_USER -> now.isBefore(userCreatedAt.plus(newUserTimeToLive))
        FIRST_PURCHASE, DAILY, GENERAL -> true
        RETURNING_USER -> false
    }
}
