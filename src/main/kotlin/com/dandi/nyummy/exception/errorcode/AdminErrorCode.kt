package com.dandi.nyummy.exception.errorcode

import org.springframework.http.HttpStatus

enum class AdminErrorCode(override val status: HttpStatus, override val code: String, override val message: String) :
    ErrorCode {
    NOT_ADMIN(HttpStatus.FORBIDDEN, "api.admin.notAdmin", "어드민으로 등록되지 않았거나 비활성화된 계정입니다."),
    ICON_NOT_FOUND(HttpStatus.NOT_FOUND, "api.admin.iconNotFound", "요청한 iconId가 데이터베이스에 존재하지 않습니다."),
    DUPLICATE_ICON_NAME(HttpStatus.CONFLICT, "api.admin.duplicateIconName", "같은 이름의 음식 아이콘이 이미 있습니다."),
    ROOM_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "api.admin.roomItemNotFound", "요청한 itemId가 데이터베이스에 존재하지 않습니다."),
    DUPLICATE_ROOM_ITEM_NAME(HttpStatus.CONFLICT, "api.admin.duplicateRoomItemName", "같은 이름의 아이템이 이미 있습니다."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "api.admin.productNotFound", "요청한 productId가 데이터베이스에 존재하지 않습니다."),
}
