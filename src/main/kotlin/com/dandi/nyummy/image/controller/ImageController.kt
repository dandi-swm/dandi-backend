package com.dandi.nyummy.image.controller

import com.dandi.nyummy.image.dto.CreateUploadUrlRequest
import com.dandi.nyummy.image.dto.UploadUrlResponse
import com.dandi.nyummy.image.service.ImageService
import com.dandi.nyummy.security.AuthUser
import com.dandi.nyummy.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Image", description = "이미지 업로드 URL 발급 API")
@RestController
@RequestMapping("/api/v1/images")
class ImageController(private val imageService: ImageService) {

    @Operation(
        summary = "이미지 업로드 URL 발급",
        description = "용도(purpose)별 경로에 이미지를 외부 스토리지로 직접 업로드할 수 있는 presigned URL과 이미지 키를 발급한다. " +
            "업로드한 이미지는 각 도메인의 생성 API(예: 식사 기록 생성)에 imageKey를 넘겨야 확정된다.",
    )
    @PostMapping("/presigned-url")
    fun createUploadUrl(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: CreateUploadUrlRequest,
    ): UploadUrlResponse = imageService.createUploadUrl(
        userId = user.userId,
        purpose = request.purpose,
        contentType = request.contentType,
        fileSizeBytes = request.fileSizeBytes,
    )
}
