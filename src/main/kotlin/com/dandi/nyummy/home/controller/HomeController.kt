package com.dandi.nyummy.home.controller

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
            "보유 코인, 연속 기록 현황(streak), 오늘의 식사 현황(남아 있는 끼니 수, 기록 시도 횟수와 상한, " +
            "섭취·목표 칼로리)이 들어 있다. " +
            "todayRecordedCount는 삭제하지 않은 식사 수이고, todayAttemptCount는 삭제·실패까지 포함한 " +
            "시도 횟수다. 등록 가능 여부는 todayAttemptCount와 todayMaxAttemptCount를 비교해 판단한다. " +
            "화면 단위로 묶은 응답이므로 각 값의 의미는 도메인별 API와 동일하다.",
    )
    @ApiResponse(responseCode = "500", description = "사용자 프로필이 존재하지 않음 (가입 시 생성되어야 하므로 데이터 이상)")
    @GetMapping
    fun home(@CurrentUser user: AuthUser): HomeResponse = homeService.getHome(user.userId)
}
