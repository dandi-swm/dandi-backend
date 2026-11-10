package com.dandi.nyummy.report.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.report.enum.ReportStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDate

/**
 * 주간 영양 리포트 한 건. 구매 한 번에 한 건이고, 기간은 [startDate](일요일, Asia/Seoul)부터 7일이다.
 *
 * [title]·[viewType]·[content]·[completedAt]은 COMPLETED일 때만 채운다. content는 생성 시점 스냅샷이라
 * 나중에 식사가 바뀌어도 그대로이고, 앱은 저장된 viewType의 네이티브 뷰로 그린다.
 *
 * 상태는 Worker·취소가 서로 다른 트랜잭션에서 바꾸므로 엔티티 필드를 고쳐 덮어쓰지 않고, 리포지토리의 조건부 갱신으로 바꾼다.
 * 엔티티로 고치는 값(읽음 시각)은 바뀐 컬럼만 쓰도록(@DynamicUpdate) 해, 그사이 다른 트랜잭션이 바꾼 상태를 옛 값으로 덮지 않는다.
 *
 * @property readAt 완료된 리포트를 처음 상세 조회한 시각. null이면 아직 안 본 리포트다
 */
@Entity
@DynamicUpdate
@EntityListeners(AuditingEntityListener::class)
@Table(name = "reports")
class Report(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "start_date", nullable = false)
    val startDate: LocalDate,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ReportStatus = ReportStatus.WAITING

    @Column(name = "title", length = 100)
    var title: String? = null

    @Column(name = "view_type", length = 50)
    var viewType: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content")
    var content: String? = null

    @Column(name = "read_at")
    var readAt: Instant? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /** 그 주의 토요일. 저장하지 않고 계산한다 */
    val endDate: LocalDate
        get() = startDate.plusDays(6)

    /** 완료됐지만 아직 상세를 열어 보지 않음 */
    val isUnread: Boolean
        get() = status == ReportStatus.COMPLETED && readAt == null

    /**
     * 완료된 리포트를 처음 읽은 시각을 기록한다. 완료 전이거나 이미 읽었으면 그대로 둔다.
     */
    fun updateReadAt(readAt: Instant) {
        if (isUnread) {
            this.readAt = readAt
        }
    }

    fun validateOwnership(requestUserId: Long) {
        if (userId != requestUserId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }
    }
}
