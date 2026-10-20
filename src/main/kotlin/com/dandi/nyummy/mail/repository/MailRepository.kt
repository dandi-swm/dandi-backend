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
     * 사용자의 안 받은 우편을 잠가(SELECT … FOR UPDATE) id 오름차순으로 조회한다. 모두 받기에 쓴다.
     * 삭제된 우편과 [now] 기준으로 만료된 우편은 제외한다. 만료 기준은 목록 조회와 같다.
     *
     * 잠그는 순서를 id 오름차순으로 고정해, 모두 받기끼리 겹쳐도 서로 반대 순서로 기다리지 않게 한다.
     * 하나 받기와 겹치면 뒤에 온 쪽이 앞쪽 커밋을 기다렸다가 최신 행을 읽으므로, 이미 받은 우편은 결과에서 빠진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select m
        from Mail as m
        where m.userId = :userId
            and m.deletedAt is null
            and m.claimedAt is null
            and (m.expiresAt is null or m.expiresAt > :now)
        order by m.id asc
    """,
    )
    fun findMailsWithLockByUserIdAndClaimedAtIsNull(
        @Param("userId") userId: Long,
        @Param("now") now: Instant,
    ): List<Mail>

    /**
     * 사용자에게 안 받은 우편이 있는지 확인한다. 홈 우편함 배지에 쓴다.
     * 조건은 모두 받기와 같다. 삭제된 우편과 [now] 기준으로 만료된 우편은 제외한다.
     *
     * 개수를 세지 않고 exists로 묻는다. 조건에 맞는 첫 행을 찾으면 더 읽지 않는다.
     */
    @Query(
        """
        select exists (
            select 1
            from Mail as m
            where m.userId = :userId
                and m.deletedAt is null
                and m.claimedAt is null
                and (m.expiresAt is null or m.expiresAt > :now)
        )
    """,
    )
    fun existsByUserIdAndClaimedAtIsNull(@Param("userId") userId: Long, @Param("now") now: Instant): Boolean

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
