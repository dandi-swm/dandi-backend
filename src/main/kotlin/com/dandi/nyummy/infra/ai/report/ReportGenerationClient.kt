package com.dandi.nyummy.infra.ai.report

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.report.dto.NutritionBalanceResult
import com.dandi.nyummy.report.dto.ReportTextResult

interface ReportGenerationClient {

    /**
     * 서버가 집계한 한 주를 보고 리포트의 AI 문장(제목·조언·제안 3개·마무리)을 쓴다.
     *
     * @param language 문장을 쓸 언어. 리포트를 구매한 요청의 앱 언어다
     * @throws ReportGenerationFailureException 실패를 [ReportGenerationFailureCategory]로 나눠 던진다
     */
    fun generateReportText(nutritionBalance: NutritionBalanceResult, language: Language): ReportTextResult
}
