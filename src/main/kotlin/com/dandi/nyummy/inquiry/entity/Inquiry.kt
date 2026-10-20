package com.dandi.nyummy.inquiry.entity

import com.dandi.nyummy.exception.BusinessException
import com.dandi.nyummy.exception.errorcode.AuthErrorCode
import com.dandi.nyummy.exception.errorcode.InquiryErrorCode
import com.dandi.nyummy.inquiry.enum.InquiryCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "inquiries")
class Inquiry(

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    var category: InquiryCategory,

    @Column(name = "question_title", nullable = false, length = 100)
    var questionTitle: String,

    @Column(name = "question_content", nullable = false, length = 2000)
    var questionContent: String,

    @Column(name = "image_key", length = 512)
    var imageKey: String? = null,

    @Column(name = "app_version", length = APP_VERSION_MAX_LENGTH)
    val appVersion: String? = null,
) {

    companion object {
        const val APP_VERSION_MAX_LENGTH = 20
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    @Column(name = "answer_title", length = 100)
    var answerTitle: String? = null

    @Column(name = "answer_content", length = 2000)
    var answerContent: String? = null

    @Column(name = "answered_at")
    var answeredAt: Instant? = null

    @Column(name = "answer_read_at")
    var answerReadAt: Instant? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    val isAnswered: Boolean
        get() = answeredAt != null

    val hasUnreadAnswer: Boolean
        get() = isAnswered && answerReadAt == null

    /**
     * 질문(유형·제목·내용·첨부 이미지)을 수정한다. 답변이 등록된 뒤에는 수정할 수 없다.
     */
    fun updateQuestion(category: InquiryCategory, questionTitle: String, questionContent: String, imageKey: String?) {
        validateQuestionUpdate()
        this.category = category
        this.questionTitle = questionTitle
        this.questionContent = questionContent
        this.imageKey = imageKey
    }

    /**
     * 답변을 처음 읽은 시각을 기록한다. 답변이 없거나 이미 읽었으면 그대로 둔다.
     */
    fun updateAnswerReadAt(answerReadAt: Instant) {
        if (isAnswered && this.answerReadAt == null) {
            this.answerReadAt = answerReadAt
        }
    }

    /**
     * 삭제 시각을 기록한다. null이면 소프트 삭제를 되돌린다.
     */
    fun updateDeletedAt(deletedAt: Instant?) {
        this.deletedAt = deletedAt
    }

    fun validateOwnership(requestUserId: Long) {
        if (userId != requestUserId) {
            throw BusinessException(AuthErrorCode.FORBIDDEN)
        }
    }

    fun validateQuestionUpdate() {
        if (isAnswered) {
            throw BusinessException(InquiryErrorCode.ALREADY_ANSWERED)
        }
    }

    /**
     * 처음 답변을 등록한다. 답변 시각은 이때 정해지고 이후 수정해도 바뀌지 않는다.
     *
     * 등록·수정 중 무엇을 부를지는 호출부가 [isAnswered]로 정하므로, 어긋나면 코드 버그라 check로 막는다.
     */
    fun createAnswer(answerTitle: String, answerContent: String, answeredAt: Instant) {
        check(!isAnswered) { "이미 답변이 등록된 문의입니다: inquiryId=$id" }
        this.answerTitle = answerTitle
        this.answerContent = answerContent
        this.answeredAt = answeredAt
    }

    /**
     * 등록된 답변의 제목·내용을 고친다. 오타 정정용이라 답변 시각과 읽음 기록은 그대로 둔다.
     */
    fun updateAnswer(answerTitle: String, answerContent: String) {
        check(isAnswered) { "답변이 없는 문의는 답변을 수정할 수 없습니다: inquiryId=$id" }
        this.answerTitle = answerTitle
        this.answerContent = answerContent
    }
}
