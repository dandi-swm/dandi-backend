package com.dandi.nyummy.coin.service

import com.dandi.nyummy.coin.entity.CoinHistory
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.repository.CoinHistoryRepository
import com.dandi.nyummy.coin.repository.CoinRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
        val coin = coinRepository.findCoinWithLockByUserId(userId)
            ?: throw IllegalStateException("가입 시 생성되어야 할 코인 행이 없습니다: userId=$userId")

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
}
