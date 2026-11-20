package com.dandi.nyummy.auth.enum

/**
 * 인증 제공자.
 *
 * [isSocial]이 true면 본인 확인을 외부 제공자가 대신하며, 계정은 providerUserId로 식별되고 비밀번호가 없다.
 * EMAIL만 이메일 + 비밀번호 계정이다. 회원가입·비밀번호 경로의 분기는 provider 값이 아니라 이 속성으로 한다 —
 * 제공자를 추가할 때 enum 한 줄로 끝나게 하기 위해서다.
 */
enum class AuthProvider(val isSocial: Boolean) {
    EMAIL(false),
    KAKAO(true),
    GOOGLE(true),
    NAVER(true),
    APPLE(true),
}
