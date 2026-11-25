package com.dandi.nyummy.meal.repository

import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.meal.entity.AnalysisFeedback
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AnalysisFeedbackRepository : JpaRepository<AnalysisFeedback, Long> {
    fun existsByMealId(mealId: Long?): Boolean

    /**
     * 어드민 분석 평가 목록. 별점 상한·이유 여부·언어로 거르고 id가 [cursor]보다 작은 평가를 최신순(id 내림차순)으로 조회한다.
     * 조건이 null이면 그 조건으로 거르지 않는다.
     *
     * 이유는 앱이 빈 문자열로 보낼 수 있어서 공백뿐인 값도 이유가 없는 것으로 본다.
     * 언어가 NULL인 평가(기기 토큰으로도 채우지 못한 예전 평가)는 [originalLanguage]로 본다.
     * 호출하는 쪽은 [Language.ORIGINAL_LANGUAGE]를 넘긴다. JPQL에 enum 값을 적으면 원본 언어가 두 곳에 생겨서 파라미터로 받는다.
     * 위치는 cursor 조건이 정하므로 [pageable]은 page 0으로 개수만 정한다.
     */
    @Query(
        """
        select f
        from AnalysisFeedback as f
        where f.id < :cursor
            and (:maxRating is null or f.rating <= :maxRating)
            and (:hasReason is null
                or (:hasReason = true and f.reason is not null and trim(f.reason) <> '')
                or (:hasReason = false and (f.reason is null or trim(f.reason) = '')))
            and (:language is null or coalesce(f.language, :originalLanguage) = :language)
        order by f.id desc
    """,
    )
    fun findAnalysisFeedbacksBeforeCursor(
        @Param("maxRating") maxRating: Float?,
        @Param("hasReason") hasReason: Boolean?,
        @Param("language") language: Language?,
        @Param("originalLanguage") originalLanguage: Language,
        @Param("cursor") cursor: Long,
        pageable: Pageable,
    ): Slice<AnalysisFeedback>
}
