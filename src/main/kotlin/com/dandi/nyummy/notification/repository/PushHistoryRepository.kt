package com.dandi.nyummy.notification.repository

import com.dandi.nyummy.notification.entity.PushHistory
import org.springframework.data.jpa.repository.JpaRepository

interface PushHistoryRepository : JpaRepository<PushHistory, Long>
