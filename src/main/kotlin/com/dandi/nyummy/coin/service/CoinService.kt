package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.dto.CoinHistoriesResponse
import com.dandi.nyummy.coin.dto.CoinHistoryResponse
import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.entity.CoinHistory
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.repository.CoinHistoryRepository
import com.dandi.nyummy.coin.repository.CoinRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class CoinService(
    private val coinRepository: CoinRepository,
    private val coinHistoryRepository: CoinHistoryRepository,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CoinService::class.java)
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
     * 이 행을 사용자 단위 자물쇠로 쓴다. 코인·우편·장바구니를 함께 바꾸는 트랜잭션은 첫 문장에서 이 메서드를 불러,
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
     * 사용자의 코인 잔액을 [quantity]만큼 늘리고 내역을 남긴다.
     *
     * 잔액 행을 잠근 뒤(SELECT … FOR UPDATE) 엔티티에서 더한다. 같은 사용자의 지급·차감이 동시에 와도
     * 차례로 반영되어 잔액이 유실되지 않고, 내역의 balanceAfter가 실제 변동 직후 잔액과 같다.
     * 잠금은 호출한 쪽 트랜잭션이 끝날 때까지 유지되므로, 다른 행과 함께 잠그는 쪽은 잠금 순서를 맞춘다.
     *
     * 지급이 걸린 일이라 잔액 행이 없으면 [getBalance]와 달리 0으로 넘어가지 않고 실패시킨다.
     *
     * @param userId 코인을 받을 사용자 ID
     * @param quantity 늘릴 코인 수(1 이상)
     * @param category 변동 종류. [sourceId]가 어느 테이블의 행인지도 정한다
     * @param sourceId 변동을 일으킨 행의 id
     * @return 늘린 뒤의 잔액
     * @throws IllegalStateException 가입 시 생성되어야 할 잔액 행이 없는 경우
     */
    @Transactional
    fun increaseBalance(userId: Long, quantity: Int, category: CoinHistoryCategory, sourceId: Long?): Int {
        val coin = lockCoin(userId)

        coin.increaseBalance(quantity)
        coinHistoryRepository.save(
            CoinHistory(
                userId = userId,
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
     * 지금은 해피케이스라 요청과 관계없이 고정 내역을 반환한다. 실제 조회는 coin_histories의 (user_id, id) 인덱스로 붙인다.
     *
     * @param cursor 직전 묶음의 마지막 coinHistoryId. null이면 가장 최신 내역부터 조회한다
     */
    fun getCoinHistories(userId: Long, cursor: Long?, size: Int): CoinHistoriesResponse = CoinHistoriesResponse(
        coinHistories = listOf(
            CoinHistoryResponse(
                coinHistoryId = 511,
                category = CoinHistoryCategory.ROOM_ITEM_PURCHASE,
                title = "발바닥 방석 외 2개 구매",
                quantity = -1620,
                balanceAfter = 860,
                createdAt = Instant.parse("2026-10-23T06:00:00Z"),
            ),
            CoinHistoryResponse(
                coinHistoryId = 498,
                category = CoinHistoryCategory.MAIL_CLAIM,
                title = "우편 받기",
                quantity = 300,
                balanceAfter = 2480,
                createdAt = Instant.parse("2026-10-22T09:00:00Z"),
            ),
            CoinHistoryResponse(
                coinHistoryId = 476,
                category = CoinHistoryCategory.MAIL_CLAIM,
                title = "우편 받기",
                quantity = 150,
                balanceAfter = 2180,
                createdAt = Instant.parse("2026-10-21T12:00:00Z"),
            ),
        ),
        nextCursor = null,
        hasNext = false,
    )
}
