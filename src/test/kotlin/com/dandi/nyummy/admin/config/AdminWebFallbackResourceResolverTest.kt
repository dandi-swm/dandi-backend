package com.dandi.nyummy.admin.config

import io.mockk.mockk
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.resource.ResourceResolverChain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 어드민 웹 정적 파일을 찾고, 화면 경로에는 index.html을 내려주되 API·없는 파일은 404로 두는지 다룬다. */
class AdminWebFallbackResourceResolverTest {

    private val webDir: Path = Files.createTempDirectory("admin-web").also {
        Files.writeString(it.resolve("index.html"), "<html></html>")
        Files.createDirectories(it.resolve("assets"))
        Files.writeString(it.resolve("assets/index-abc.js"), "console.log(1)")
    }
    private val location = FileSystemResource("$webDir/")
    private val resolver = AdminWebFallbackResourceResolver()

    @AfterTest
    fun deleteWebDir() {
        webDir.toFile().deleteRecursively()
    }

    private fun resolve(path: String): Resource? =
        resolver.resolveResource(MockHttpServletRequest(), path, listOf(location), mockk<ResourceResolverChain>())

    @Test
    fun `파일이 있으면 그 파일을 내려준다`() {
        assertEquals("index-abc.js", resolve("assets/index-abc.js")?.filename)
    }

    @Test
    fun `화면 주소는 index html을 내려준다`() {
        assertEquals("index.html", resolve("inquiries/12")?.filename)
        assertEquals("index.html", resolve("login")?.filename)
    }

    @Test
    fun `없는 API 경로는 index html로 대신하지 않는다`() {
        assertNull(resolve("v1/unknown"))
    }

    @Test
    fun `없는 파일은 index html로 대신하지 않는다`() {
        assertNull(resolve("assets/old.js"))
    }
}
