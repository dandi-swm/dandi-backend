package com.dandi.nyummy.cat.controller

import com.dandi.nyummy.cat.dto.CatAnimationResponse
import com.dandi.nyummy.cat.dto.CatResponse
import com.dandi.nyummy.cat.dto.CreateCatRequest
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.service.CatService
import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.CatErrorCode
import com.dandi.nyummy.exception.errorcode.S3ErrorCode
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Cat", description = "고양이 캐릭터 정보 및 체형별 애니메이션 조회 API")
@RestController
@RequestMapping("/api/v1/cats")
class CatController(private val catService: CatService) {

    @Operation(
        summary = "고양이 생성",
        description = "고양이를 만들고 온보딩에서 받은 끼니 시각을 프로필에 저장한다. 사용자당 한 마리만 생성할 수 있다. " +
            "이름은 ${Cat.NAME_MAX_LENGTH}자 이하이고(String 길이, 이모지는 2자 이상), 넘으면 api.cat.nameTooLong이다.",
    )
    @ApiResponse(responseCode = "201", description = "고양이 생성 성공")
    @ApiErrorCodes(
        cat = [CatErrorCode.NAME_TOO_LONG, CatErrorCode.CAT_ALREADY_EXISTS],
        user = [UserErrorCode.PROFILE_NOT_FOUND],
    )
    @PostMapping
    fun createCat(@CurrentUser user: AuthUser, @Valid @RequestBody request: CreateCatRequest): ResponseEntity<Void> {
        catService.createCat(user.userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @Operation(
        summary = "고양이 조회",
        description = "로그인한 사용자의 고양이 정보(이름, 체형, 애정도, 경험치)를 조회한다. " +
            "체형은 CatWeight 이름으로 반환된다. " +
            "저장된 값을 그대로 읽기만 하며 체형 평가는 하지 않는다.",
    )
    @ApiErrorCodes(cat = [CatErrorCode.CAT_NOT_FOUND])
    @GetMapping
    fun getCat(@CurrentUser user: AuthUser): CatResponse = catService.getCat(user.userId)

    @Operation(
        summary = "고양이 애니메이션 조회",
        description = "고양이의 현재 체형에 해당하는 애니메이션 메타데이터를 조회한다. " +
            "감정 상태별로 스프라이트 시트 경로, 프레임 정보, 대사가 들어 있다. " +
            "각 이미지의 전체 URL은 baseUrl 뒤에 animation의 src를 그대로 이어붙여 만든다. " +
            "animation은 [동작 그룹][그룹 내 순차 재생 clip] 2단 배열이며, " +
            "loop가 true인 clip은 다음 clip으로 넘어가기 전 반복하는 지속 구간이다.",
    )
    @ApiErrorCodes(
        cat = [CatErrorCode.CAT_NOT_FOUND, CatErrorCode.ANIMATION_METADATA_INVALID],
        s3 = [S3ErrorCode.OBJECT_READ_FAILED],
    )
    @GetMapping("/animations")
    fun getCatAnimations(@CurrentUser user: AuthUser): CatAnimationResponse = catService.getCatAnimations(user.userId)
}
