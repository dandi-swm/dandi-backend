package com.dandi.nyummy.shop.repository

import com.dandi.nyummy.room.enum.RoomItemCategory
import com.dandi.nyummy.shop.entity.ShopBanner
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ShopBannerRepository : JpaRepository<ShopBanner, Long> {

    /**
     * [now]에 노출 중인 배너(startsAt <= now < endsAt, 종료 시각이 없으면 계속 노출)를 노출 순서대로 조회한다.
     * 노출 순서가 같으면 최신(id 내림차순)이 앞이다. 알리는 프로모션이 진행 중이고 사용자가 대상인지는 서비스가 가른다.
     */
    @Query(
        """
            select b
            from ShopBanner as b
            where b.startsAt <= :now
                and (b.endsAt is null or b.endsAt > :now)
            order by b.displayOrder asc, b.id desc
        """,
    )
    fun findActiveShopBanners(@Param("now") now: Instant): List<ShopBanner>
}
