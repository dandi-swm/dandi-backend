package com.dandi.nyummy.report.service

import com.dandi.nyummy.coin.entity.Coin
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.coin.service.CoinService
import com.dandi.nyummy.report.entity.ReportPurchaseRefund
import com.dandi.nyummy.report.enum.ReportPurchaseRefundCategory
import com.dandi.nyummy.report.repository.ReportPurchaseRefundRepository
import com.dandi.nyummy.report.repository.ReportPurchaseRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 리포트 구매 환불. 생성 실패(Worker)와 사용자 취소가 함께 쓴다.
 */
@Service
class ReportPurchaseRefundService(
    private val coinService: CoinService,
    private val reportPurchaseRepository: ReportPurchaseRepository,
    private val reportPurchaseRefundRepository: ReportPurchaseRefundRepository,
) {

    /**
     * 리포트의 구매 가격을 돌려주고 환불 기록과 코인 내역을 남긴다. 무료 구매는 돌려줄 코인이 없어 아무것도 남기지 않는다.
     *
     * 호출한 쪽 트랜잭션이 첫 문장에서 코인 행을 잠그고, 리포트를 CANCELED·FAILED로 바꾼 뒤에 부른다. 같은 구매를 두 번 환불하려 하면
     * report_purchase_id 유니크 제약이 막아 트랜잭션이 롤백된다(코인도 돌려주지 않는다).
     *
     * @param coin [CoinService.lockCoin]으로 잠근 코인 행
     * @return 돌려준 코인. 무료 구매면 0
     * @throws IllegalStateException 리포트의 구매가 없는 경우(구매 트랜잭션이 함께 만들므로 데이터가 깨진 것이다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createReportPurchaseRefund(coin: Coin, reportId: Long, category: ReportPurchaseRefundCategory): Int {
        val reportPurchase = reportPurchaseRepository.findByReportId(reportId)
            ?: throw IllegalStateException("리포트의 구매가 없습니다: reportId=$reportId")
        if (reportPurchase.purchasePrice == 0) {
            return 0
        }

        val refund = reportPurchaseRefundRepository.saveAndFlush(
            ReportPurchaseRefund(
                reportPurchaseId = reportPurchase.id,
                category = category,
                coinQuantity = reportPurchase.purchasePrice,
            ),
        )
        coinService.increaseBalance(
            coin,
            refund.coinQuantity,
            CoinHistoryCategory.REPORT_PURCHASE_REFUND,
            refund.id,
        )
        return refund.coinQuantity
    }
}
