package com.dandi.nyummy.language

import com.dandi.nyummy.language.enum.Language
import org.springframework.context.MessageSource

/**
 * 서버 고정 문구를 [language]로 꺼낸다. 키가 없으면 [org.springframework.context.NoSuchMessageException]이 난다.
 *
 * 인자가 없으면 MessageFormat을 거치지 않아 작은따옴표가 그대로 찍힌다(MessagesTest가 ''를 막는다).
 */
fun MessageSource.getMessage(key: String, language: Language, vararg args: Any): String =
    getMessage(key, args, language.toLocale())
