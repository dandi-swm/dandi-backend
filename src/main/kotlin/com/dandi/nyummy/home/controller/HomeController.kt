package com.dandi.nyummy.home.controller

import com.dandi.nyummy.config.openapi.ApiErrorCodes
import com.dandi.nyummy.exception.errorcode.UserErrorCode
import com.dandi.nyummy.home.dto.HomeResponse
import com.dandi.nyummy.home.service.HomeService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Home", description = "홈 화면에 필요한 여러 도메인의 값을 한 번에 조회하는 API")
@RestController
@RequestMapping("/api/v1/home")
class HomeController(private val homeService: HomeService) {
    @Operation(
        summary = "홈 화면 조회",
        description = "홈 화면을 그리는 데 필요한 값을 한 번에 조회한다. " +
            "보유 코인, 연속 기록 현황(streak), 오늘의 식사 현황(기록한 끼니 수, 섭취·목표 칼로리), " +
            "안 읽은 문의 답변 여부(hasUnreadInquiryAnswer), 마이룸에 장착한 아이템(equippedRoomItems)이 들어 있다. " +
            "equippedRoomItems는 마이룸 조회 API의 equippedRoomItems와 같으며 zIndex 오름차순이다. " +
            "todayRecordedCount는 삭제하지 않은 식사 수이고, todayAttemptCount는 삭제·실패까지 포함한 " +
            "시도 횟수다. 등록 가능 여부는 todayAttemptCount와 todayMaxAttemptCount를 비교해 판단한다. " +
            "화면 단위로 묶은 응답이므로 각 값의 의미는 도메인별 API와 동일하다.",
    )
    @ApiErrorCodes(user = [UserErrorCode.PROFILE_NOT_FOUND])
    @GetMapping
    fun getHome(@CurrentUser user: AuthUser): HomeResponse = homeService.getHome(user.userId)
}
