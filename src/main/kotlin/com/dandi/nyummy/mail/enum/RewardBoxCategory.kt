package com.dandi.nyummy.mail.enum

/**
 * 보상 상자 우편의 등급. 우편 제목으로 쓰는 이름은 서버 고정 문구 파일의 `rewardBoxCategory.<이름>`에 있다.
 *
 * [id]는 상자 우편 목록 응답의 imageId로 내려가는 번호다. 앱은 이 번호로 상자 이미지 경로(`reward-boxes/{id}.png`)를 조립한다.
 * DB에는 등급 이름을 저장하므로 번호는 저장하지 않는다. 앱이 이미 쓰는 번호라 바꾸지 않는다.
 */
enum class RewardBoxCategory(val id: Int) {
    BRONZE(1),
    SILVER(2),
    GOLD(3),
}
