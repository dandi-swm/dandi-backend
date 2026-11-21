package com.dandi.nyummy.language.enum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `한국어는 가나나 한자가 있으면 맞지 않는다`() {
        assertTrue(Language.KO.matchesScript("냉동도시락과 군고구마"))
        assertFalse(Language.KO.matchesScript("냉동도시락과 焼きいも"))
        assertFalse(Language.KO.matchesScript("다음에는 サラダ를 먹어보라냥"))
        assertFalse(Language.KO.matchesScript("牛丼"))
    }

    @Test
    fun `일본어는 한글이 있으면 맞지 않는다`() {
        assertTrue(Language.JA.matchesScript("カレーライスと白ご飯"))
        assertFalse(Language.JA.matchesScript("カレーライスと김치"))
        assertFalse(Language.JA.matchesScript("단백질이 부족하다냥"))
    }

    @Test
    fun `영어는 한글과 가나와 한자가 있으면 맞지 않는다`() {
        assertTrue(Language.EN.matchesScript("Kimchi stew"))
        assertFalse(Language.EN.matchesScript("Kimchi 찌개"))
        assertFalse(Language.EN.matchesScript("Curry with きんぴら"))
        assertFalse(Language.EN.matchesScript("Beef 丼"))
    }

    @Test
    fun `숫자와 영문과 문장 부호는 어느 언어에서나 맞는다`() {
        Language.entries.forEach { language ->
            assertTrue(language.matchesScript("Coca-Cola Zero 500ml (0kcal)!"))
            assertTrue(language.matchesScript(""))
        }
    }
}
