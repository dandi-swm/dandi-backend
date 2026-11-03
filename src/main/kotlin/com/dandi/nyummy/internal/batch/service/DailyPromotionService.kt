package com.dandi.nyummy.internal.batch.service

import com.dandi.nyummy.shop.calculator.pickDailyProducts
import com.dandi.nyummy.shop.config.ShopProperties
import com.dandi.nyummy.shop.entity.Product
import com.dandi.nyummy.shop.entity.Promotion
import com.dandi.nyummy.shop.entity.PromotionProduct
import com.dandi.nyummy.shop.enum.PromotionCategory
import com.dandi.nyummy.shop.enum.ShopTab
import com.dandi.nyummy.shop.repository.ProductRepository
import com.dandi.nyummy.shop.repository.PromotionProductRepository
import com.dandi.nyummy.shop.repository.PromotionRepository
import com.dandi.nyummy.shop.service.ShopService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

@Service
class DailyPromotionService(
    private val promotionRepository: PromotionRepository,
    private val promotionProductRepository: PromotionProductRepository,
    private val productRepository: ProductRepository,
    private val shopProperties: ShopProperties,
    private val clock: Clock,
    private val random: Random,
) {
    companion object {
        private val ZONE = ZoneId.of("Asia/Seoul")

        private val NAME_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd")

        private val logger = LoggerFactory.getLogger(DailyPromotionService::class.java)
    }

    /**
     * 하루치 오늘의 할인 프로모션을 만든다. 자정 전에 다음 날 것을 미리 만들어, 실패해도 자정 전에 재시도할 수 있다.
     *
     * 그날과 겹치는 오늘의 할인이 이미 있으면 만들지 않는다. 운영자가 직접 만든 프로모션이 우선이고, 재시도해도 두 번 만들어지지 않는다.
     * 동시에 두 번 돌면 둘 다 통과하므로 잡의 동시 실행 금지로 막는다.
     *
     * 고를 상품이 없으면 만들지 않는다. 추천 탭은 상품이 없는 섹션을 빼므로 화면이 깨지지 않는다.
     *
     * @param date 만들 날짜(KST). 생략하면 내일이다. 놓친 날을 복구할 때 넘긴다
     */
    @Transactional
    fun createDailyPromotion(date: LocalDate? = null) {
        val targetDate = date ?: LocalDate.now(clock.withZone(ZONE)).plusDays(1)
        val startsAt = targetDate.atStartOfDay(ZONE).toInstant()
        val endsAt = targetDate.plusDays(1).atStartOfDay(ZONE).toInstant()

        if (promotionRepository.existsPromotionInPeriod(PromotionCategory.DAILY, startsAt, endsAt)) {
            logger.info("오늘의 할인 생성 건너뜀: 이미 있음, date={}", targetDate)
            return
        }

        val products = pickDailyProducts(
            candidates = getCandidates(startsAt, endsAt),
            count = ShopService.DAILY_PRODUCT_COUNT,
            random = random,
        )
        if (products.isEmpty()) {
            logger.error("오늘의 할인 생성 실패: 고를 상품이 없음, date={}", targetDate)
            return
        }

        val promotion = promotionRepository.save(
            Promotion(
                name = "${targetDate.format(NAME_DATE_FORMATTER)} 오늘의 할인",
                category = PromotionCategory.DAILY,
                discountRate = shopProperties.dailyDiscountRate,
                startsAt = startsAt,
                endsAt = endsAt,
            ),
        )
        promotionProductRepository.saveAll(
            products.mapIndexed { index, product ->
                PromotionProduct(promotionId = promotion.id, productId = product.id, displayOrder = index)
            },
        )

        logger.info(
            "오늘의 할인 생성 완료: date={}, promotionId={}, productIds={}",
            targetDate,
            promotion.id,
            products.map { it.id },
        )
    }

    // 그날 내내 판매 중인 인테리어 상품. 방 배경은 이미 가진 사용자가 살 수 없고, 기간 한정은 그 자체가 판매 방식이라 뺀다.
    private fun getCandidates(startsAt: Instant, endsAt: Instant): List<Product> =
        productRepository.findOnSaleProducts(startsAt).filter { product ->
            ShopTab.INTERIOR.contains(product.category) &&
                !product.isLimited &&
                product.saleEndsAt?.isBefore(endsAt) != true
        }
}
