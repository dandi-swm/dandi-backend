package com.dandi.nyummy.item.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

/**
 * 아이템(상품 정의). 도메인 고유 속성은 확장 엔티티([com.dandi.nyummy.room.entity.RoomItem])가 갖는다.
 *
 * 이미지 URL은 저장하지 않고 id로 CDN 고정 경로를 만들어 쓴다.
 * [price]가 null이면 상점에서 팔지 않는 아이템(보상 전용)이다.
 */
@Entity
@EntityListeners(AuditingEntityListener::class)
@Table(name = "items")
class Item(
    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "price")
    var price: Int? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    val id: Long = 0L

    /** [name]의 번역. 키는 언어 코드이고 원본(한국어)은 넣지 않는다. null이면 모든 언어에서 [name]으로 응답한다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "name_translations")
    var nameTranslations: Map<String, String>? = null

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    /**
     * 이름을 바꾼다. 보유 아이템 목록·우편 보상은 이름을 저장하지 않고 여기서 읽으므로 바로 바뀐다.
     */
    fun updateName(name: String) {
        this.name = name
    }

    /**
     * 가격을 바꾼다. null이면 상점에서 팔지 않는 아이템(보상 전용)이 된다.
     */
    fun updatePrice(price: Int?) {
        this.price = price
    }
}
