package com.dandi.nyummy.admin.enum

import java.util.UUID

/**
 * 어드민이 올리는 이미지의 용도. 객체 키는 `{prefix}/{id}.png`이고, 앱은 CDN 주소에 이 키를 붙여 URL을 조립한다.
 * 상점 배너만 예외로, 서버가 imageUrl을 내려주므로 키를 DB(`shop_banners.image_key`)에 두고 임의 이름을 쓴다.
 *
 * 앱과 같은 규칙을 여기 한 곳에만 둔다. 어드민 미리보기 URL과 `icons.image_url`도 이 규칙으로 만든다.
 *
 * @property prefix S3 객체 키의 최상위 경로
 * @property maxFileSizeBytes 허용되는 최대 파일 크기(byte)
 */
enum class AdminImagePurpose(val prefix: String, val maxFileSizeBytes: Long) {

    ICON("icons", 512 * 1024),

    // 마이룸 전용 경로가 아니라 아이템 공통 경로를 쓴다. items가 모든 아이템(이후 고양이 치장 포함)의 공통 부모라
    // id가 겹치지 않고, 우편은 아이템 종류를 모른 채 items/{itemId}.png로 조립하기 때문이다.
    ROOM_ITEM("items", 5 * 1024 * 1024),

    // 이미지를 바꿀 때 같은 키에 덮어쓰지 않고 새 키에 올린다. URL이 바뀌니 CDN 무효화가 필요 없고,
    // 기기에 1시간 남는 옛 이미지 캐시도 피한다. 옛 키는 이전 배너 기록이라 지우지 않는다.
    SHOP_BANNER("shop-banners", 5 * 1024 * 1024),
    ;

    companion object {
        const val CONTENT_TYPE = "image/png"

        // CDN은 길게 들고 있다가 이미지를 바꿀 때만 무효화한다. 무효화가 닿지 않는 기기 캐시는 1시간으로 짧게 둔다.
        const val CACHE_CONTROL = "public, max-age=3600, s-maxage=31536000"
    }

    /** @param id 음식 아이콘은 iconId, 마이룸 아이템은 itemId */
    fun createObjectKey(id: Long): String = "$prefix/$id.png"

    fun createImageUrl(cdnBaseUrl: String, id: Long): String = "$cdnBaseUrl/${createObjectKey(id)}"

    /** 상점 배너처럼 키를 DB에 두는 이미지에 쓴다. 올릴 때마다 다른 키가 나온다 */
    fun createUniqueObjectKey(): String = "$prefix/${UUID.randomUUID()}.png"
}
