package com.dandi.nyummy.infra.ai.comment

import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureCategory
import com.dandi.nyummy.infra.ai.nutrition.AnalysisFailureException
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.dto.CatCommentInput

interface CatCommentClient {

    /**
     * 방금 기록한 식사에 대한 고양이 코멘트를 만든다.
     *
     * @param language 코멘트를 쓸 언어. 식사를 기록한 요청의 앱 언어다
     * @throws AnalysisFailureException 실패를 [AnalysisFailureCategory]로 나눠 던진다
     */
    fun createCatComment(input: CatCommentInput, language: Language): String
}
