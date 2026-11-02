package com.bcz.bilivideoparser

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.*

class BiliLoginApiTest {
    private val headers = listOf(
        "SESSDATA=synthetic%2Cvalue=withEquals; Domain=.bilibili.com; Path=/; Secure; HttpOnly",
        "bili_jct=synthetic-csrf; Domain=.bilibili.com; Path=/; Secure"
    )

    @Test
    fun `generate accepts a passport QR URL and returns its key`() {
        val api = BiliLoginApi { url ->
            assertTrue(url.endsWith("/qrcode/generate"))
            BiliHttpResponse("""{"code":0,"data":{"url":"https://passport.bilibili.com/h5-app/passport/login/scan?qrcode_key=example","qrcode_key":"example"}}""")
        }
        assertEquals("example", api.generate().key)
    }

    @Test
    fun `generate supports the current account domain but rejects unrelated login URLs`() {
        val current = BiliLoginApi {
            BiliHttpResponse("""{"code":0,"data":{"url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?key=example","qrcode_key":"example"}}""")
        }
        assertTrue(current.generate().url.startsWith("https://account.bilibili.com/"))
        val unrelated = BiliLoginApi {
            BiliHttpResponse("""{"code":0,"data":{"url":"https://account.bilibili.com.example.org/scan","qrcode_key":"example"}}""")
        }
        assertFailsWith<IOException> { unrelated.generate() }
    }

    @Test
    fun `poll distinguishes waiting scanned expired and confirmed states`() {
        val codes = listOf(86101, 86090, 86038, 0).iterator()
        val api = BiliLoginApi {
            BiliHttpResponse("""{"code":0,"data":{"code":${codes.next()},"refresh_token":"synthetic-token"}}""", headers)
        }
        assertEquals(BiliLoginApi.Status.WAITING, api.poll("key").status)
        assertEquals(BiliLoginApi.Status.SCANNED, api.poll("key").status)
        assertEquals(BiliLoginApi.Status.EXPIRED, api.poll("key").status)
        val confirmed = api.poll("key")
        assertEquals(BiliLoginApi.Status.CONFIRMED, confirmed.status)
        assertEquals("synthetic%2Cvalue=withEquals", confirmed.cookies.first { it.name == "SESSDATA" }.value)
        assertEquals("synthetic-token", confirmed.refreshToken)
    }

    @Test
    fun `login requires real Set-Cookie credentials even if redirect URL contains credentials`() {
        val api = BiliLoginApi {
            BiliHttpResponse("""{"code":0,"data":{"code":0,"url":"https://passport.biligame.com/crossDomain?SESSDATA=secret","refresh_token":"secret"}}""")
        }
        val error = assertFailsWith<IOException> { api.poll("key") }
        assertFalse(error.message!!.contains("secret"))
    }

    @Test
    fun `unknown login status does not expose response contents`() {
        val api = BiliLoginApi { BiliHttpResponse("""{"code":0,"data":{"code":999,"message":"secret"}}""") }
        val error = assertFailsWith<IOException> { api.poll("private-key") }
        assertTrue(error.message!!.contains("999"))
        assertFalse(error.message!!.contains("private-key"))
        assertFalse(error.message!!.contains("secret"))
    }

    @Test
    fun `account reports login membership and expired credentials`() {
        val member = BiliLoginApi { BiliHttpResponse("""{"code":0,"data":{"isLogin":true,"uname":"Example","mid":123,"vipStatus":1}}""") }.account()
        assertTrue(member.loggedIn)
        assertTrue(member.vip)
        assertEquals(123L, member.uid)
        assertFalse(BiliLoginApi { BiliHttpResponse("""{"code":-101,"data":{"isLogin":false}}""") }.account().loggedIn)
    }

    @Test
    fun `QR image decodes to the original login URL`() {
        val file = File.createTempFile("bili-qr-test-", ".png")
        try {
            val url = "https://passport.bilibili.com/h5-app/passport/login/scan?qrcode_key=synthetic"
            BiliLoginService.writeQrCode(url, file)
            val image = ImageIO.read(file)
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))))
            assertEquals(url, decoded.text)
        } finally {
            file.delete()
        }
    }
}
