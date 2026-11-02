package com.bcz.bilivideoparser

import org.junit.Assume.assumeTrue
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.*

class BiliDynamicLiveTest {
    @Test
    fun `reported Opus has its title body and two readable original images`() {
        assumeTrue(System.getenv("BILIURL_LIVE_TESTS") == "true")
        val http = BiliHttpClient()
        try {
            http.prepareBrowserSession()
            val result = assertNotNull(BiliDynamicClient(request = { http.get(it).body }, warning = ::println)
                .getDynamic("1259705064636809218"))
            assertEquals("老莉莉12", result.userName)
            assertTrue(result.content.contains("肯德基新品肉霸堡"))
            assertTrue(result.content.contains("bro！"))
            assertEquals(2, result.pictures.size)
            for (url in result.pictures) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", BiliHttpClient.USER_AGENT)
                    connection.setRequestProperty("Referer", "https://www.bilibili.com/")
                    connection.setRequestProperty("Range", "bytes=0-31")
                    assertTrue(connection.responseCode in listOf(200, 206))
                    val header = connection.inputStream.use { it.readNBytes(16) }
                    assertTrue(header.size >= 2 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte(), "Original image must be a JPEG")
                } finally {
                    connection.disconnect()
                }
            }
            println("Verified reported Opus: title, body, author, and two readable JPEG images")
        } finally {
            http.close()
        }
    }
}
