package com.dandi.nyummy.language.enum

import java.lang.Character.UnicodeScript
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

    /** `MessageSource`에 넘길 Locale. 서버 고정 문구를 꺼낼 때만 쓴다. */
    fun toLocale(): Locale = Locale.of(code)

    /**
     * [text]에 이 언어로 쓴 글에는 나오지 않을 글자가 없는지. 모델이 요청한 언어가 아닌 언어를 섞어 썼는지 가려내는 데 쓴다.
     *
     * 언어를 알아내는 것이 아니라 글자 종류만 본다. 한국어는 가나와 한자, 일본어는 한글, 영어는 한글과 가나와 한자가 있으면 맞지 않는다.
     * 숫자, 영문, 문장 부호는 어느 언어의 글에도 나오므로 맞는 것으로 본다.
     * 그래서 로마자로 적은 다른 언어("Kimchi jjigae")와, 한자만 있는 글이 일본어인지 중국어인지는 가리지 못한다.
     *
     * @return 맞지 않는 글자가 하나도 없으면 true. 빈 문자열도 true
     */
    fun matchesScript(text: String): Boolean {
        // when으로 고르면 언어를 추가할 때 여기서 컴파일이 깨져 빠뜨리지 않는다
        val otherScripts = when (this) {
            KO -> JAPANESE_SCRIPTS
            JA -> KOREAN_SCRIPTS
            EN -> KOREAN_SCRIPTS + JAPANESE_SCRIPTS
        }

        return text.codePoints().noneMatch { UnicodeScript.of(it) in otherScripts }
    }

    companion object {
        private val KOREAN_SCRIPTS = setOf(UnicodeScript.HANGUL)
        private val JAPANESE_SCRIPTS = setOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN)

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
