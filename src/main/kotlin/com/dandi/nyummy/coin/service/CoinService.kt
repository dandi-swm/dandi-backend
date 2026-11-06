package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.dto.CoinHistoriesResponse
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.entity.CoinHistory
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.mapper.toCoinHistoryResponse
import com.dandi.nyummy.coin.repository.CoinHistoryRepository
import com.dandi.nyummy.coin.repository.CoinRepository
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class CoinService(
    private val coinRepository: CoinRepository,
    private val coinHistoryRepository: CoinHistoryRepository,
    private val itemPurchaseProductRepository: ItemPurchaseProductRepository,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CoinService::class.java)
        private const val MAIL_CLAIM_TITLE = "우편 받기"
        private const val ITEM_PURCHASE_TITLE = "상점 구매"
        private const val IN_APP_PURCHASE_TITLE = "코인 구매"
        private const val COIN_GIFT_TITLE = "코인 선물"
        private const val ITEM_PURCHASE_REFUND_TITLE = "상점 환불"
    }

    /**
     * 사용자의 코인 잔액을 조회한다.
     *
     * coins 행은 가입 트랜잭션에서 함께 생성되고, 그 전에 가입한 사용자는 V0.41이 채웠으므로 없을 수 없다.
     * 없다면 데이터가 깨진 것이므로 추적할 수 있게 userId를 남기고, 화면이 깨지지 않도록 0을 반환한다.
     *
     * @param userId 조회하는 사용자 ID
     * @return 코인 잔액
     */
    @Transactional(readOnly = true)
    fun getBalance(userId: Long): Int {
        val coin = coinRepository.findByUserId(userId)
            ?: run {
                logger.error("가입 시 생성되어야 할 코인 행이 없습니다: userId={}", userId)
                return 0
            }

        return coin.balance
    }

    /**
     * 사용자의 코인 행을 잠가(SELECT … FOR UPDATE) 반환한다.
     *
     * 이 행을 사용자 단위 자물쇠로 쓴다. 코인·우편·장바구니·보유 아이템을 바꾸는 트랜잭션은 첫 문장에서 이 메서드를 불러,
     * 같은 사용자의 요청을 하나씩 처리하고 서로 반대 순서로 기다리지 않게 한다. 첫 문장이어야 REPEATABLE READ의
     * 스냅샷이 잠금 뒤에 생겨 이후 조회가 앞 요청이 커밋한 데이터를 본다.
     *
     * 잠금은 호출한 쪽 트랜잭션이 끝날 때 풀리므로, 트랜잭션 없이 부르면 실패시킨다.
     *
     * @throws IllegalStateException 가입 시 생성되어야 할 코인 행이 없는 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockCoin(userId: Long): Coin = coinRepository.findCoinWithLockByUserId(userId)
        ?: throw IllegalStateException("가입 시 생성되어야 할 코인 행이 없습니다: userId=$userId")

    /**
     * 이미 잠근 코인 행의 잔액을 [quantity]만큼 늘리고 내역을 남긴다.
     *
     * 지급하는 쪽은 트랜잭션 첫 문장에서 [lockCoin]으로 코인 행을 잠그므로, 그 행을 받아 다시 조회하지 않는다.
     * 같은 트랜잭션이 다시 잠가도 기다리지는 않지만 같은 쿼리가 한 번 더 나가고, 모두 받기에서는 우편 수만큼 나간다.
     *
     * @param coin [lockCoin]으로 잠근 코인 행
     * @param quantity 늘릴 코인 수(1 이상)
     * @param category 변동 종류. [sourceId]가 어느 테이블의 행인지도 정한다
     * @param sourceId 변동을 일으킨 행의 id
     * @return 늘린 뒤의 잔액
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun increaseBalance(coin: Coin, quantity: Int, category: CoinHistoryCategory, sourceId: Long?): Int {
        coin.increaseBalance(quantity)
        coinHistoryRepository.save(
            CoinHistory(
                userId = coin.userId,
                category = category,
                quantity = quantity,
                balanceAfter = coin.balance,
                sourceId = sourceId,
            ),
        )

        return coin.balance
    }

    /**
     * 이미 잠근 코인 행의 잔액을 [quantity]만큼 줄이고 내역을 남긴다. 내역의 수량은 음수로 남는다.
     *
     * [lockCoin]으로 잠근 행을 받아 다시 조회하지 않는다. 0코인 결제는 변동이 없으므로 호출하지 않는다.
     *
     * @param coin [lockCoin]으로 잠근 코인 행
     * @param quantity 줄일 코인 수(1 이상, 잔액 이하)
     * @param category 변동 종류. [sourceId]가 어느 테이블의 행인지도 정한다
     * @param sourceId 변동을 일으킨 행의 id
     * @return 줄인 뒤의 잔액
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun decreaseBalance(coin: Coin, quantity: Int, category: CoinHistoryCategory, sourceId: Long): Int {
        coin.decreaseBalance(quantity)
        coinHistoryRepository.save(
            CoinHistory(
                userId = coin.userId,
                category = category,
                quantity = -quantity,
                balanceAfter = coin.balance,
                sourceId = sourceId,
            ),
        )

        return coin.balance
    }

    /**
     * 사용자의 코인 내역을 최신순으로 [size]개씩 조회한다. 무한 스크롤용 cursor 방식이다.
     *
     * 구매 내역의 문구에 상품 이름이 들어가므로, 묶음에 든 구매의 상품 이름을 한 번의 쿼리로 읽는다.
     * 문구는 조회할 때 만들어 운영자가 상품 이름을 바꾸면 지난 내역의 문구도 바뀐다.
     *
     * @param cursor 직전 묶음의 마지막 coinHistoryId. null이면 가장 최신 내역부터 조회한다
     */
    @Transactional(readOnly = true)
    fun getCoinHistories(userId: Long, cursor: Long?, size: Int): CoinHistoriesResponse {
        val coinHistories = coinHistoryRepository.findByUserIdAndIdLessThanOrderByIdDesc(
            userId = userId,
            cursor = cursor ?: Long.MAX_VALUE,
            pageable = PageRequest.of(0, size),
        )
        val productNames = getItemPurchaseProductNames(coinHistories.content)

        return CoinHistoriesResponse(
            coinHistories = coinHistories.content.map { coinHistory ->
                coinHistory.toCoinHistoryResponse(title = createTitle(coinHistory, productNames))
            },
            nextCursor = if (coinHistories.hasNext()) coinHistories.content.last().id else null,
            hasNext = coinHistories.hasNext(),
        )
    }

    // 구매 id별 구매 상품 이름. 묶음에 구매가 없으면 쿼리하지 않는다.
    private fun getItemPurchaseProductNames(coinHistories: List<CoinHistory>): Map<Long, List<String>> {
        val itemPurchaseIds = coinHistories
            .filter { it.category == CoinHistoryCategory.ROOM_ITEM_PURCHASE }
            .mapNotNull { it.sourceId }
        if (itemPurchaseIds.isEmpty()) return emptyMap()

        return itemPurchaseProductRepository.findItemPurchaseProductNames(itemPurchaseIds)
            .groupBy({ it.itemPurchaseId }, { it.name })
    }

    private fun createTitle(coinHistory: CoinHistory, productNames: Map<Long, List<String>>): String =
        when (coinHistory.category) {
            CoinHistoryCategory.MAIL_CLAIM -> MAIL_CLAIM_TITLE
            CoinHistoryCategory.ROOM_ITEM_PURCHASE -> createItemPurchaseTitle(productNames[coinHistory.sourceId])
            CoinHistoryCategory.IN_APP_PURCHASE -> IN_APP_PURCHASE_TITLE
            CoinHistoryCategory.COIN_GIFT -> COIN_GIFT_TITLE
            CoinHistoryCategory.ITEM_PURCHASE_REFUND -> ITEM_PURCHASE_REFUND_TITLE
        }

    // 상품 1개는 "발바닥 방석 구매", 여러 개는 같은 상품이어도 1개당 한 행이라 "발바닥 방석 외 2개 구매"다
    private fun createItemPurchaseTitle(productNames: List<String>?): String = when {
        productNames.isNullOrEmpty() -> ITEM_PURCHASE_TITLE
        productNames.size == 1 -> "${productNames.first()} 구매"
        else -> "${productNames.first()} 외 ${productNames.size - 1}개 구매"
    }
}
