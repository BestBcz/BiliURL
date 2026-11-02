package com.bcz.bilivideoparser

import org.junit.Assume.assumeTrue
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Optional smoke test: set BILIURL_LIVE_TESTS=true to contact Bilibili. */
class BiliApiLiveTest {
    @Test
    fun `passport generates a QR ticket and reports an unscanned state`() {
        assumeTrue(System.getenv("BILIURL_LIVE_TESTS") == "true")
        val http = BiliHttpClient()
        try {
            http.prepareBrowserSession()
            val login = BiliLoginApi(http::get)
            val ticket = login.generate()
            assertTrue(ticket.url.startsWith("https://passport.bilibili.com/") || ticket.url.startsWith("https://account.bilibili.com/"))
            assertEquals(BiliLoginApi.Status.WAITING, login.poll(ticket.key).status)
            println("Verified QR generation and unscanned polling response; no account login performed")
        } finally {
            http.close()
        }
    }

    @Test
    fun `reported videos have metadata and readable MP4 streams`() {
        assumeTrue(System.getenv("BILIURL_LIVE_TESTS") == "true")
        val client = BiliApiClient(warning = { println(it) })
        for (bvId in listOf("BV1D3Hx6jEhD", "BV1pMqDB6EkJ")) {
            val video = client.getVideoData(bvId)
            assertEquals(bvId, video["bvid"].asString)
            val urls = client.getPlaybackUrls(bvId, video["cid"].asLong, "16")
            var readable = false
            for (url in urls) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", BiliApiClient.USER_AGENT)
                    connection.setRequestProperty("Referer", "https://www.bilibili.com/video/$bvId")
                    connection.setRequestProperty("Range", "bytes=0-31")
                    if (connection.responseCode !in listOf(200, 206)) continue
                    val header = connection.inputStream.use { it.readNBytes(32) }
                    readable = header.size >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                    if (readable) break
                } catch (e: IOException) {
                    println("CDN read failed: ${e.message}")
                } finally {
                    connection.disconnect()
                }
            }
            assertTrue(readable, "$bvId must return an MP4 file header")
            println("Verified $bvId: cid=${video["cid"]}, readable MP4, ${urls.size} CDN candidates")
        }
    }
}
