package com.bcz.bilivideoparser

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.io.IOException
import kotlin.test.*

class BiliHttpClientTest {
    @Test
    fun `HTTP client decodes compressed browser responses without leaking cookies to other hosts`() {
        val server = MockWebServer()
        val http = BiliHttpClient({ "SESSDATA=synthetic-secret" })
        try {
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeUtf8("{\"code\":0}") }
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(compressed))
            assertEquals("{\"code\":0}", http.get(server.url("/example").toString()).body)
            assertNull(server.takeRequest().getHeader("Cookie"))
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test
    fun `HTTP errors include the status but omit QR keys and query strings`() {
        val server = MockWebServer()
        val http = BiliHttpClient()
        try {
            server.enqueue(MockResponse().setResponseCode(412).setBody("secret response"))
            val error = assertFailsWith<IOException> { http.get(server.url("/poll?qrcode_key=secret-key").toString()) }
            assertTrue(error.message!!.contains("412"))
            assertFalse(error.message!!.contains("secret"))
        } finally {
            http.close()
            server.shutdown()
        }
    }
}
