package com.dandi.nyummy.coin.repository

import com.dandi.nyummy.coin.entity.Coin
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.stereotype.Repository

@Repository
interface CoinRepository : JpaRepository<Coin, Long> {

    fun findByUserId(userId: Long): Coin?

    // SELECT … FOR UPDATE. 같은 사용자의 잔액 변경을 이 행 하나로 줄 세운다(트랜잭션이 끝날 때까지 잠긴다).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findCoinWithLockByUserId(userId: Long): Coin?
}
