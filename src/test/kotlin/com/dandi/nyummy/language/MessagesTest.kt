package com.dandi.nyummy.language

import com.dandi.nyummy.cat.enum.CatWeight
import com.dandi.nyummy.coin.enum.CoinHistoryCategory
import com.dandi.nyummy.language.enum.Language
import com.dandi.nyummy.mail.enum.RewardBoxCategory
import com.dandi.nyummy.meal.enum.MealTime
import com.dandi.nyummy.room.enum.RoomItemCategory
import java.io.InputStreamReader
import java.text.MessageFormat
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * 서버 고정 문구 파일(messages*.properties)이 언어마다 빠짐없이 맞는지 검사한다.
 *
 * 키가 빠지면 그 언어 사용자에게 한국어가 나가는데, 출시 전에는 눈에 잘 띄지 않아 테스트로 막는다.
 */
class MessagesTest {

    private val originalMessages = loadMessages(Language.ORIGINAL_LANGUAGE)

    // 라벨을 문구 파일에 둔 enum. 값을 추가하면 키가 없어 실행 중에 NoSuchMessageException이 나므로 여기서 막는다.
    private val labeledEnums: Map<String, List<Enum<*>>> = mapOf(
        "mealTime" to MealTime.entries,
        "roomItemCategory" to RoomItemCategory.entries,
        "catWeight" to CatWeight.entries,
        "coinHistoryCategory" to CoinHistoryCategory.entries,
        "rewardBoxCategory" to RewardBoxCategory.entries,
    )

    @Test
    fun `라벨을 문구 파일에 둔 enum은 모든 값의 키가 있다`() {
        labeledEnums.forEach { (prefix, entries) ->
            val missingKeys = entries.map { "$prefix.${it.name}" }.filterNot { originalMessages.containsKey(it) }

            assertEquals(emptyList(), missingKeys, "messages.properties에 enum 값의 키가 없다")
        }
    }

    @Test
    fun `모든 언어 파일이 원본과 같은 키를 가진다`() {
        translatedLanguages().forEach { language ->
            val translatedKeys = loadMessages(language).stringPropertyNames()

            assertEquals(
                originalMessages.stringPropertyNames(),
                translatedKeys,
                "messages_${language.code}.properties의 키가 원본과 다르다. " +
                    "빠진 키: ${originalMessages.stringPropertyNames() - translatedKeys}, " +
                    "남는 키: ${translatedKeys - originalMessages.stringPropertyNames()}",
            )
        }
    }

    @Test
    fun `원본 문구의 인자가 작은따옴표에 먹히지 않는다`() {
        originalMessages.stringPropertyNames().forEach { key ->
            val pattern = originalMessages.getProperty(key)

            assertEquals(
                findWrittenArgumentIndexes(pattern),
                findFormattedArgumentIndexes(pattern, Language.ORIGINAL_LANGUAGE),
                "$key: 적은 인자와 실제로 채워지는 인자가 다르다. 작은따옴표를 ''로 썼는지 확인한다",
            )
        }
    }

    @Test
    fun `번역 문구는 원본과 같은 인자를 채운다`() {
        translatedLanguages().forEach { language ->
            val translatedMessages = loadMessages(language)

            originalMessages.stringPropertyNames().forEach { key ->
                val translatedPattern = translatedMessages.getProperty(key) ?: return@forEach

                assertEquals(
                    findFormattedArgumentIndexes(originalMessages.getProperty(key), Language.ORIGINAL_LANGUAGE),
                    findFormattedArgumentIndexes(translatedPattern, language),
                    "messages_${language.code}.properties의 $key: 원본과 채우는 인자가 다르다. " +
                        "인자를 빠뜨렸거나 작은따옴표에 먹혔는지 확인한다",
                )
            }
        }
    }

    @Test
    fun `인자가 없는 문구는 작은따옴표를 두 번 쓰지 않는다`() {
        // 인자 없이 꺼내면 MessageFormat을 거치지 않아 ''가 그대로 찍힌다.
        Language.entries.forEach { language ->
            val messages = loadMessages(language)

            messages.stringPropertyNames()
                .filter { findWrittenArgumentIndexes(messages.getProperty(it)).isEmpty() }
                .forEach { key ->
                    assertFalse(
                        messages.getProperty(key).contains("''"),
                        "${language.code} $key: 인자가 없는 문구는 작은따옴표를 한 번만 쓴다",
                    )
                }
        }
    }

    @Test
    fun `언어마다 해당 파일의 문구를 꺼낸다`() {
        val messageSource = createTestMessageSource()

        Language.entries.forEach { language ->
            val expected = MessageFormat(
                loadMessages(language).getProperty("push.mealReminder.body"),
                language.toLocale(),
            )
                .format(arrayOf("냐미"))

            assertEquals(
                expected,
                messageSource.getMessage("push.mealReminder.body", arrayOf("냐미"), language.toLocale()),
                "${language.code}: Language.toLocale()이 해당 언어 파일로 이어지지 않는다",
            )
        }
    }

    private fun translatedLanguages(): List<Language> = Language.entries - Language.ORIGINAL_LANGUAGE

    // 원본 언어는 messages.properties, 나머지는 messages_<code>.properties다. Language를 추가하면 파일이 없어서 실패한다.
    private fun loadMessages(language: Language): Properties {
        val fileName = if (language ==
            Language.ORIGINAL_LANGUAGE
        ) {
            "messages.properties"
        } else {
            "messages_${language.code}.properties"
        }
        val stream = javaClass.classLoader.getResourceAsStream(fileName)
        assertNotNull(stream, "$fileName 이 없다")

        return Properties().apply { InputStreamReader(stream, Charsets.UTF_8).use { load(it) } }
    }

    private fun findWrittenArgumentIndexes(pattern: String): Set<Int> =
        ARGUMENT_REGEX.findAll(pattern).map { it.groupValues[1].toInt() }.toSet()

    // 인자 자리마다 표시를 넣어 포맷한 뒤, 결과에 실제로 찍힌 표시만 모은다.
    private fun findFormattedArgumentIndexes(pattern: String, language: Language): Set<Int> {
        val formatted = MessageFormat(pattern, language.toLocale()).format(Array(MAX_ARGUMENT_COUNT) { createMark(it) })
        return (0 until MAX_ARGUMENT_COUNT).filter { formatted.contains(createMark(it)) }.toSet()
    }

    private fun createMark(index: Int): String = "<arg$index>"

    private companion object {
        val ARGUMENT_REGEX = Regex("""\{(\d+)""")
        const val MAX_ARGUMENT_COUNT = 10
    }
}
