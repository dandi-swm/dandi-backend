package com.dandi.nyummy.language.enum

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTest {

    @Test
    fun `지원하는 언어 코드는 그 언어로 바꾼다`() {
        assertEquals(Language.KO, Language.from("ko"))
        assertEquals(Language.JA, Language.from("ja"))
        assertEquals(Language.EN, Language.from("en"))
    }

    @Test
    fun `지역이 붙은 값은 앞부분 언어 코드만 본다`() {
        assertEquals(Language.JA, Language.from("ja-JP"))
        assertEquals(Language.JA, Language.from("ja_JP"))
        assertEquals(Language.EN, Language.from("en-SG"))
    }

    @Test
    fun `대소문자와 앞뒤 공백은 무시한다`() {
        assertEquals(Language.JA, Language.from(" JA "))
        assertEquals(Language.KO, Language.from("Ko-KR"))
    }

    @Test
    fun `헤더가 없거나 비었으면 원본 언어로 본다`() {
        assertEquals(Language.ORIGINAL_LANGUAGE, Language.from(null))
        assertEquals(Language.ORIGINAL_LANGUAGE, Language.from(""))
        assertEquals(Language.ORIGINAL_LANGUAGE, Language.from("  "))
    }

    @Test
    fun `지원하지 않는 언어는 대체 언어로 본다`() {
        assertEquals(Language.FALLBACK_LANGUAGE, Language.from("fr"))
        assertEquals(Language.FALLBACK_LANGUAGE, Language.from("zh-Hans-SG"))
        assertEquals(Language.FALLBACK_LANGUAGE, Language.from("-JP"))
    }

    @Test
    fun `원본 언어는 한국어이고 대체 언어는 영어다`() {
        assertEquals(Language.KO, Language.ORIGINAL_LANGUAGE)
        assertEquals(Language.EN, Language.FALLBACK_LANGUAGE)
    }
}
