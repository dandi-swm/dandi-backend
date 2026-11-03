package com.dandi.nyummy.notification.repository

import com.dandi.nyummy.notification.entity.PushHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PushHistoryRepository : JpaRepository<PushHistory, Long> {

    /**
     * 사용자의 전송 내역을 전부 물리 삭제한다. 탈퇴 익명화에서 호출한다.
     *
     * 문의 답변 알림의 본문에 답변 제목이 들어가 문의를 지워도 내용이 남으므로 함께 지운다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PushHistory p where p.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: Long): Int
}
