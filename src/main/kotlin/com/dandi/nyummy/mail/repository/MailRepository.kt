package com.dandi.nyummy.mail.repository

import com.dandi.nyummy.mail.entity.Mail
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface MailRepository : JpaRepository<Mail, Long> {

    // SELECT … FOR UPDATE. 같은 우편을 동시에 받으려는 요청을 이 행 하나로 줄 세운다(트랜잭션이 끝날 때까지 잠긴다).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findMailWithLockByIdAndDeletedAtIsNull(mailId: Long): Mail?

    /**
     * id가 [cursor]보다 작은 우편을 최신순(id 내림차순)으로 조회한다.
     * 삭제된 우편과 [now] 기준으로 만료된 우편은 받은 우편이어도 제외한다. expiresAt이 null이면 만료되지 않는다.
     *
     * 위치는 cursor 조건이 정하므로 [pageable]은 page 0으로 개수만 정한다.
     * Slice는 size + 1개를 읽어 다음 묶음이 있는지 판단한다.
     */
    @Query(
        """
        select m
        from Mail as m
        where m.userId = :userId
            and m.deletedAt is null
            and (m.expiresAt is null or m.expiresAt > :now)
            and m.id < :cursor
        order by m.id desc
    """,
    )
    fun findMailsByUserIdBeforeCursor(
        @Param("userId") userId: Long,
        @Param("cursor") cursor: Long,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): Slice<Mail>
}
