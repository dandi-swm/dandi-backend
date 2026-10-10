package com.dandi.nyummy.image.enum

/**
 * 이미지 업로드 용도. 값을 추가하면 해당 [prefix] 디렉토리로 업로드·확정이 동작한다.
 *
 * 객체 키는 `{prefix}/{userId}/{년}/{월}/{일}/{UUID}.{확장자}` 형식이며,
 * 확정 시 `{prefix}/{userId}/`로 시작하는지 검사하므로 다른 용도로 발급받은 키는 확정할 수 없다.
 *
 * @property prefix S3 객체 키의 최상위 경로
 * @property maxFileSizeBytes 허용되는 최대 파일 크기(byte)
 */
enum class ImagePurpose(val prefix: String, val maxFileSizeBytes: Long) {

    MEAL("meals", 10 * 1024 * 1024),
    INQUIRY("inquiries", 10 * 1024 * 1024),
}
