package com.bcz.bilivideoparser

import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class BiliHttpResponse(val body: String, val setCookies: List<String> = emptyList())

/** One browser session shared by login, metadata and playback requests. */
internal class BiliHttpClient(
    private val configuredCookie: () -> String = { "" },
    private val warning: (String) -> Unit = {}
) {
    val cookieJar = BiliCookieJar(configuredCookie)
    private var prepared = false
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun get(url: String): BiliHttpResponse {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Origin", "https://www.bilibili.com")
            .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Never include a login token, query string or response body in errors.
                    throw IOException("HTTP ${response.code} (${request.url.host}${request.url.encodedPath})")
                }
                return BiliHttpResponse(response.body?.string() ?: throw IOException("B站响应为空"), response.headers.values("Set-Cookie"))
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP ") == true) throw e
            throw IOException("B站网络请求失败 (${request.url.host}${request.url.encodedPath})", e)
        } catch (_: IllegalArgumentException) {
            // OkHttp can include a malformed header value in this exception.
            throw IOException("Cookie 格式无效，请清空手动 Cookie 后重新扫码登录")
        }
    }

    @Synchronized
    fun prepareBrowserSession() {
        if (prepared) return
        prepared = true
        try {
            get("https://www.bilibili.com/")
        } catch (_: IOException) {
            warning("B站首页会话初始化失败，将继续尝试接口")
        }
        val existing = cookieJar.loadForRequest("https://api.bilibili.com/".toHttpUrl()).map { it.name }.toSet() + parseCookieHeader(configuredCookie()).keys
        if ("buvid3" in existing && "buvid4" in existing) return
        try {
            val json = JsonParser.parseString(get("https://api.bilibili.com/x/frontend/finger/spi").body).asJsonObject
            if (json.get("code")?.asInt == 0) {
                val data = json.getAsJsonObject("data")
                for ((field, name) in mapOf("b_3" to "buvid3", "b_4" to "buvid4")) {
                    if (name in existing) continue
                    data?.get(field)?.asString?.takeIf { it.isNotBlank() }?.let { value ->
                        cookieJar.saveFromResponse("https://api.bilibili.com/".toHttpUrl(), listOf(
                            Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").secure().build()
                        ))
                    }
                }
            }
        } catch (_: Exception) {
            warning("B站设备 Cookie 初始化失败，将使用已有会话")
        }
    }

    @Synchronized
    fun clearSession() {
        cookieJar.replaceAll(emptyList())
        prepared = false
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        fun isBilibiliHost(host: String) = host == "bilibili.com" || host.endsWith(".bilibili.com")
        fun parseCookieHeader(header: String): Map<String, String> {
            require(!header.contains('\r') && !header.contains('\n')) { "Cookie 必须为一行文本" }
            return header.split(';').mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()
        }
    }
}

internal class BiliCookieJar(private val configuredCookie: () -> String = { "" }) : CookieJar {
    private val values = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!BiliHttpClient.isBilibiliHost(url.host)) return
        for (cookie in cookies.filter { BiliHttpClient.isBilibiliHost(it.domain) }) {
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) values.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        if (!BiliHttpClient.isBilibiliHost(url.host)) return emptyList()
        val configured = BiliHttpClient.parseCookieHeader(configuredCookie()).map { (name, value) ->
            Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").secure().build()
        }.filter { it.matches(url) }
        // Merge inside the CookieJar so OkHttp's bridge keeps both device cookies and login credentials.
        return (configured.associateBy { it.name } + values.filter { it.matches(url) }.associateBy { it.name }).values.toList()
    }

    @Synchronized
    fun snapshot(): List<Cookie> = values.filter { it.expiresAt > System.currentTimeMillis() }.toList()

    @Synchronized
    fun replaceAll(cookies: List<Cookie>) {
        values.clear()
        values.addAll(cookies.filter { BiliHttpClient.isBilibiliHost(it.domain) && it.expiresAt > System.currentTimeMillis() })
    }
}
