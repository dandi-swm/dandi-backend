package com.dandi.nyummy.exception

import org.springframework.dao.CannotAcquireLockException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

class GlobalExceptionHandlerTest {

    @RestController
    private class ThrowingController {
        @GetMapping("/lock-failure")
        fun throwLockFailure(): Unit = throw CannotAcquireLockException("Lock wait timeout exceeded")

        @GetMapping("/unknown-failure")
        fun throwUnknownFailure(): Unit = throw IllegalStateException("예상하지 못한 오류")
    }

    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ThrowingController())
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `행 잠금을 얻지 못하면 500이 아니라 다시 시도할 수 있는 409로 응답한다`() {
        mockMvc.get("/lock-failure").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("api.common.requestConflict") }
        }
    }

    @Test
    fun `그 밖의 예외는 500으로 응답한다`() {
        mockMvc.get("/unknown-failure").andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("api.common.internalServerError") }
        }
    }
}
