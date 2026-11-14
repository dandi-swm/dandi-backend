package com.dandi.nyummy.language

import com.dandi.nyummy.language.enum.Language

/**
 * 운영자가 입력한 문구(상품 이름·배너 제목 등)를 [language]로 고른다. 받는 쪽은 그 문구의 번역 컬럼 값이다.
 *
 * 원본은 엔티티의 원래 컬럼(name·title 등)에 있고, 번역 컬럼에는 원본 언어가 아닌 언어만 [Language.code]를 키로 담는다.
 * 원본 언어이거나, 그 언어 번역이 없거나 비어 있으면 [original]을 돌려준다. 번역을 SQL로 직접 넣으므로 빈 값도 없는 것으로 본다.
 */
fun Map<String, String>?.pickTranslation(language: Language, original: String): String {
    if (language == Language.ORIGINAL_LANGUAGE) return original

    return this?.get(language.code)?.takeIf { it.isNotBlank() } ?: original
}
