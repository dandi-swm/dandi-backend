package com.dandi.nyummy.inquiry.repository

import com.dandi.nyummy.inquiry.entity.Inquiry
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface InquiryRepository : JpaRepository<Inquiry, Long> {
    fun findInquiryByIdAndDeletedAtIsNull(inquiryId: Long): Inquiry?

    fun existsByImageKey(imageKey: String): Boolean

    /**
     * id가 [cursor]보다 작은 문의를 최신순(id 내림차순)으로 조회한다.
     *
     * 위치는 cursor 조건이 정하므로 [pageable]은 page 0으로 개수만 정한다.
     * Slice는 size + 1개를 읽어 다음 묶음이 있는지 판단한다.
     */
    @Query(
        """
        select i
        from Inquiry as i
        where i.userId = :userId
            and i.deletedAt is null
            and i.id < :cursor
        order by i.id desc
    """,
    )
    fun findInquiriesByUserIdBeforeCursor(
        @Param("userId") userId: Long,
        @Param("cursor") cursor: Long,
        pageable: Pageable,
    ): Slice<Inquiry>

    @Query(
        """
        select COUNT(i) > 0
        from Inquiry as i
        where i.userId = :userId
            and i.deletedAt is null
            and i.answeredAt is not null
            and i.answerReadAt is null
    """,
    )
    fun existsUnreadAnswerByUserId(@Param("userId") userId: Long): Boolean

    /**
     * 사용자의 문의를 전부 물리 삭제한다. 탈퇴 익명화에서 호출한다.
     *
     * 질문·답변 본문에 개인정보가 섞이고 questionTitle·questionContent가 NOT NULL이라 익명화할 수
     * 없어 행을 지운다. 소프트 삭제된 문의도 함께 지운다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Inquiry i where i.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: Long): Int
}
