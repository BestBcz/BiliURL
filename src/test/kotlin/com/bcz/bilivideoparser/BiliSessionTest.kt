package com.bcz.bilivideoparser

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class BiliSessionTest {
    private val api = "https://api.bilibili.com/".toHttpUrl()
    private fun credential(name: String, value: String) = Cookie.Builder().name(name).value(value)
        .domain("bilibili.com").path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 60_000).build()

    @Test
    fun `shared device cookies do not replace manually configured authentication`() {
        val jar = BiliCookieJar { "SESSDATA=manual; rpdid=has'quotes=inside" }
        jar.saveFromResponse(api, listOf(credential("buvid3", "device-id")))
        val values = jar.loadForRequest(api).associate { it.name to it.value }
        assertEquals("manual", values["SESSDATA"])
        assertEquals("has'quotes=inside", values["rpdid"])
        assertEquals("device-id", values["buvid3"])
    }

    @Test
    fun `QR session overrides old manual auth and never travels to a CDN or another site`() {
        val jar = BiliCookieJar { "SESSDATA=old-manual" }
        jar.saveFromResponse(api, listOf(credential("SESSDATA", "new-qr")))
        assertEquals("new-qr", jar.loadForRequest(api).first { it.name == "SESSDATA" }.value)
        assertTrue(jar.loadForRequest("https://cdn.bilivideo.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://bilibili.com.example.org/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `expired response cookies remove old credentials`() {
        val jar = BiliCookieJar()
        jar.saveFromResponse(api, listOf(credential("SESSDATA", "new-qr")))
        jar.saveFromResponse(api, listOf(Cookie.Builder().name("SESSDATA").value("").domain("bilibili.com").path("/").expiresAt(1).build()))
        assertTrue(jar.loadForRequest(api).isEmpty())
    }

    @Test
    fun `session JSON preserves credentials across restart without YAML quoting`() {
        val directory = Files.createTempDirectory("bili-session-test-").toFile()
        val file = File(directory, "session.json")
        try {
            val store = BiliLoginStore(file)
            store.save(listOf(credential("SESSDATA", "synthetic=encoded%2Cvalue'"), credential("bili_jct", "csrf")), "refresh")
            val restored = BiliLoginStore(file).load()!!
            assertEquals("synthetic=encoded%2Cvalue'", restored.cookies.first { it.name == "SESSDATA" }.value)
            assertEquals("refresh", restored.refreshToken)
            assertTrue(restored.cookies.all { it.secure && it.httpOnly && !it.hostOnly })
            store.save(restored.cookies, "replacement-refresh")
            assertEquals("replacement-refresh", store.load()!!.refreshToken)
            assertEquals(listOf("session.json"), directory.listFiles()!!.map { it.name })
            store.clear()
            assertNull(store.load())
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test
    fun `corrupt session errors do not print stored secrets`() {
        val file = File.createTempFile("bili-corrupt-session-", ".json")
        try {
            file.writeText("{SESSDATA=synthetic-secret}")
            val error = assertFailsWith<IOException> { BiliLoginStore(file).load() }
            assertFalse(error.message!!.contains("synthetic-secret"))
        } finally {
            file.delete()
        }
    }
}
