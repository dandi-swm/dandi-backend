package com.dandi.nyummy.coin.repository

import com.dandi.nyummy.coin.entity.CoinHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CoinHistoryRepository : JpaRepository<CoinHistory, Long>
