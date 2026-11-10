package com.dandi.nyummy.language

import org.springframework.context.MessageSource
import org.springframework.context.support.ResourceBundleMessageSource

/**
 * application.yaml의 spring.messages와 같은 설정으로 실제 문구 파일을 읽는다.
 *
 * 단위 테스트에서 문구를 mock으로 두면 키를 잘못 적어도 통과하므로, 실제 파일에서 꺼내 키와 언어까지 검증한다.
 */
fun createTestMessageSource(): MessageSource = ResourceBundleMessageSource().apply {
    setBasename("messages")
    setDefaultEncoding(Charsets.UTF_8.name())
    setFallbackToSystemLocale(false)
}
