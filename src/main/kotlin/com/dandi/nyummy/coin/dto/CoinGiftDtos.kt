@file:Suppress("ktlint:standard:filename")

package com.dandi.nyummy.coin.dto

/**
 * @property coinPackageId 화면의 패키지 번호(1~3). 가격은 그룹에 따라 달라 앱에 고정돼 있다
 */
data class ClaimCoinGiftRequest(val coinPackageId: Long)

/**
 * @property coinQuantity 선물로 받은 코인. 고른 패키지와 관계없이 같다
 * @property balance 받은 뒤 잔액
 */
data class CoinGiftResponse(val coinQuantity: Int, val balance: Int)
