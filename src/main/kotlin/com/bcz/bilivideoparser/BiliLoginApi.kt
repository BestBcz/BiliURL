package com.bcz.bilivideoparser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.URLEncoder

internal class BiliLoginApi(private val request: (String) -> BiliHttpResponse) {
    data class QrCode(val url: String, val key: String)
    enum class Status { WAITING, SCANNED, EXPIRED, CONFIRMED }
    data class PollResult(val status: Status, val cookies: List<Cookie> = emptyList(), val refreshToken: String = "")
    data class Account(val loggedIn: Boolean, val name: String = "", val uid: Long = 0, val vip: Boolean = false)

    fun generate(): QrCode {
        val data = data(request("$PASSPORT/generate"))
        val url = data["url"].asString
        val key = data["qrcode_key"].asString
        val parsed = url.toHttpUrl()
        if (parsed.scheme != "https" || parsed.host !in setOf("passport.bilibili.com", "account.bilibili.com") || key.isBlank()) {
            throw IOException("二维码响应无效")
        }
        return QrCode(url, key)
    }

    fun poll(key: String): PollResult {
        val url = "$PASSPORT/poll?qrcode_key=${URLEncoder.encode(key, "UTF-8")}"
        val response = request(url)
        val data = data(response)
        return when (val code = data["code"].asInt) {
            86101 -> PollResult(Status.WAITING)
            86090 -> PollResult(Status.SCANNED)
            86038 -> PollResult(Status.EXPIRED)
            0 -> {
                // Set-Cookie is authoritative. Never parse credentials from the redirect URL.
                val cookies = response.setCookies.mapNotNull { Cookie.parse(url.toHttpUrl(), it) }
                    .filter { BiliHttpClient.isBilibiliHost(it.domain) && it.expiresAt > System.currentTimeMillis() }
                val names = cookies.filter { it.value.isNotBlank() }.map { it.name }.toSet()
                if (!names.containsAll(setOf("SESSDATA", "bili_jct"))) {
                    throw IOException("扫码成功响应缺少 SESSDATA 或 bili_jct，请重新扫码")
                }
                PollResult(Status.CONFIRMED, cookies, data.get("refresh_token")?.asString.orEmpty())
            }
            else -> throw IOException("扫码登录返回未知状态 code=$code")
        }
    }

    fun account(): Account {
        val response = JsonParser.parseString(request("https://api.bilibili.com/x/web-interface/nav").body).asJsonObject
        if (response.get("code")?.asInt == -101) return Account(false)
        val data = data(BiliHttpResponse(response.toString()))
        val loggedIn = data.get("isLogin")?.asBoolean == true
        return Account(loggedIn, data.get("uname")?.asString.orEmpty(), data.get("mid")?.asLong ?: 0,
            data.get("vipStatus")?.asInt == 1 || data.get("vip")?.takeIf { it.isJsonObject }?.asJsonObject?.get("status")?.asInt == 1)
    }

    private fun data(response: BiliHttpResponse): JsonObject {
        val json = JsonParser.parseString(response.body).asJsonObject
        val code = json.get("code")?.asInt ?: throw IOException("B站登录接口缺少状态码")
        if (code != 0) throw IOException("B站登录接口 code=$code")
        return json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: throw IOException("B站登录接口缺少 data")
    }

    companion object {
        private const val PASSPORT = "https://passport.bilibili.com/x/passport-login/web/qrcode"
    }
}
