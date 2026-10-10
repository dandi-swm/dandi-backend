package com.dandi.nyummy.image.validator

import com.dandi.nyummy.image.enum.ImagePurpose

/**
 * 용도별 업로드 URL 발급 전 검증. 사전 검증이 필요한 용도만 구현해 빈으로 등록한다.
 *
 * [com.dandi.nyummy.image.service.ImageService]가 발급 직전에 [purpose]가 일치하는 구현을 실행한다.
 * 업로드 이후 확정 단계의 검증은 confirmUpload의 콜백으로 넘긴다.
 */
interface ImageUploadValidator {

    /** 이 검증이 적용되는 업로드 용도. 용도당 구현은 하나만 허용된다 */
    val purpose: ImagePurpose

    /**
     * 업로드 URL을 발급해도 되는지 검증한다. 거부하려면 예외를 던진다.
     *
     * @param userId 업로드를 요청한 사용자 ID
     */
    fun validate(userId: Long)
}
