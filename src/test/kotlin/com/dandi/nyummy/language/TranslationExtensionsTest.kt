package com.dandi.nyummy.language

import com.dandi.nyummy.language.enum.Language
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationExtensionsTest {

    private val translations = mapOf("ja" to "肉球クッション", "en" to "Paw Cushion")

    @Test
    fun `요청 언어의 번역이 있으면 번역을 준다`() {
        assertEquals("肉球クッション", translations.pickTranslation(Language.JA, "발바닥 방석"))
        assertEquals("Paw Cushion", translations.pickTranslation(Language.EN, "발바닥 방석"))
    }

    @Test
    fun `원본 언어면 번역 컬럼에 값이 있어도 원본을 준다`() {
        val withKorean = translations + ("ko" to "다른 이름")

        assertEquals("발바닥 방석", withKorean.pickTranslation(Language.KO, "발바닥 방석"))
    }

    @Test
    fun `번역이 없거나 그 언어 번역이 없거나 비어 있으면 원본을 준다`() {
        assertEquals("발바닥 방석", null.pickTranslation(Language.JA, "발바닥 방석"))
        assertEquals("발바닥 방석", mapOf("en" to "Paw Cushion").pickTranslation(Language.JA, "발바닥 방석"))
        assertEquals("발바닥 방석", mapOf("ja" to " ").pickTranslation(Language.JA, "발바닥 방석"))
    }
}
