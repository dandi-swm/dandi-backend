package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.repository.ItemPurchaseProductRepository
import com.dandi.nyummy.shop.repository.ProductRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class ProductSalesCountService(
    private val productRepository: ProductRepository,
    private val itemPurchaseProductRepository: ItemPurchaseProductRepository,
    private val shopProperties: ShopProperties,
    private val clock: Clock,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ProductSalesCountService::class.java)
    }

    /**
     * 상품마다 최근 판매량을 다시 세어 값이 바뀐 상품만 고친다. 기간 안에 팔리지 않은 상품은 0이 된다.
     *
     * 트랜잭션을 걸지 않아 상품마다 따로 커밋한다. 구매는 상품을 FK로 참조하며 그 행에 공유 잠금을 거는데,
     * 여러 행의 잠금을 커밋까지 쥐고 있으면 여러 상품을 사는 구매와 교착이 날 수 있다.
     * 매번 처음부터 다시 세므로 재시도하거나 중간에 실패해도 다음 실행에서 맞춰진다.
     */
    fun updateSalesCounts() {
        val startsAt = clock.instant().minus(shopProperties.salesCountTimeToLive)
        val salesCounts = itemPurchaseProductRepository.findSalesCounts(startsAt)
            .associate { it.productId to it.purchasedCount.toInt() }

        val changedProducts = productRepository.findAll().filter { it.salesCount != (salesCounts[it.id] ?: 0) }
        changedProducts.forEach { productRepository.updateSalesCount(it.id, salesCounts[it.id] ?: 0) }

        logger.info("상품 판매량 갱신 완료: startsAt={}, changed={}", startsAt, changedProducts.size)
    }
}
