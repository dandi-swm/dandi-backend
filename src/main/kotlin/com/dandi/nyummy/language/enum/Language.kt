package com.dandi.nyummy.language.enum

import java.util.Locale

/**
 * 서버가 문구를 내려줄 수 있는 언어. 앱이 지금 화면에 쓰는 언어를 `X-Language` 헤더로 받아 정한다.
 *
 * DB에는 enum 이름(KO·JA·EN)을 저장한다. [code]는 헤더 값과 번역 JSON의 키에 쓰는 언어 코드다.
 */
enum class Language(val code: String) {
    KO("ko"),
    JA("ja"),
    EN("en"),
    ;

    companion object {
        /** 모든 문구의 원본 언어. 헤더를 보내지 않는 예전 앱 빌드와 번역이 없는 콘텐츠에 쓴다. */
        val ORIGINAL_LANGUAGE = KO

        /** 지원하지 않는 언어일 때 쓰는 언어. 원본 언어(한국어)보다 읽을 수 있는 사용자가 많다. */
        val FALLBACK_LANGUAGE = EN

        /**
         * `X-Language` 헤더 값을 언어로 바꾼다. `ja`, `ja-JP`, `ja_JP`, `JA`처럼 와도 앞부분 언어 코드만 본다.
         *
         * @param header 헤더 값. 앱이 보내지 않았으면 null
         * @return 헤더가 없거나 비었으면 [ORIGINAL_LANGUAGE], 지원하지 않거나 알아볼 수 없는 값이면 [FALLBACK_LANGUAGE]
         */
        fun from(header: String?): Language {
            // 헤더가 없는 건 예전 앱 빌드라 원본 언어로 본다.
            // 헤더를 보냈는데 언어 부분이 비었으면("-JP") 새 앱 빌드의 이상한 값이라 대체 언어로 본다.
            if (header.isNullOrBlank()) return ORIGINAL_LANGUAGE

            val code = header.trim()
                .substringBefore('-')
                .substringBefore('_')
                .lowercase(Locale.ROOT)

            return entries.firstOrNull { it.code == code } ?: FALLBACK_LANGUAGE
        }
    }
}
