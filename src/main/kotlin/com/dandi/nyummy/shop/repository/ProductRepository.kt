package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.entity.Product
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ProductRepository : JpaRepository<Product, Long> {

    /**
     * 상품을 최신순(id 내림차순)으로 조회한다. [category]가 null이면 모든 분류를 조회한다. 판매가 끝난 상품도 포함한다.
     */
    @Query(
        """
        select p
        from Product as p
        where (:category is null or p.category = :category)
        order by p.id desc
    """,
    )
    fun findProducts(@Param("category") category: RoomItemCategory?): List<Product>

    /**
     * [now]에 판매 중인 상품(saleStartsAt <= now < saleEndsAt, 종료 시각이 없으면 계속 판매)을 모두 조회한다.
     * 결제가가 사용자마다 달라 정렬은 서비스가 한다.
     */
    @Query(
        """
            select p
            from Product p
            where p.saleStartsAt <= :now
                and (p.saleEndsAt is null or p.saleEndsAt > :now)
        """,
    )
    fun findOnSaleProducts(@Param("now") now: Instant): List<Product>
}
