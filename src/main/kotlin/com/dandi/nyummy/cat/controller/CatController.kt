package com.dandi.nyummy.cat.controller

import com.dandi.nyummy.cat.dto.CatResponse
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Cat", description = "고양이 캐릭터 체형 조회 API")
@RestController
@RequestMapping("/api/v1/cat")
class CatController(private val catService: CatService) {

    // TODO: intro API가 생기면 updateCatWeight 호출을 intro API로 옮기고, 여기서는 getCatWeight를 호출한다.
    @Operation(
        summary = "고양이 체형 조회",
        description = "고양이의 현재 체형 단계(-2 홀쭉냥 ~ 2 뚱냥이)와 표시 이름을 조회한다. " +
            "마지막 평가 이후 평가 주기(3일)가 지났으면 직전 구간의 섭취 칼로리를 목표 섭취량과 비교해 " +
            "체형을 한 단계 올리거나 내린 뒤 그 결과를 반환한다. " +
            "평가 주기가 지나지 않았으면 저장된 체형을 그대로 반환한다. " +
            "체형 변화는 구간당 최대 한 단계이며, 양 끝(-2, 2)에서는 더 변하지 않는다.",
    )
    @GetMapping("/{catId}/weight")
    fun getWeight(
        @CurrentUser user: AuthUser,
        @Parameter(description = "고양이 ID") @PathVariable catId: Long,
    ): CatResponse = catService.updateCatWeight(user.userId, catId)
}
