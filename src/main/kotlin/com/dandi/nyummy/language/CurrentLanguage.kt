package com.dandi.nyummy.language

// 컨트롤러 파라미터에 현재 요청의 Language를 주입한다. 등록은 WebMvcConfig.
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentLanguage
