package com.bcz.bilivideoparser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.IOException
import java.io.StringReader
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Bilibili requests and response validation, independent of the Mirai runtime. */
internal class BiliApiClient(
    private val cookie: () -> String = { "" },
    private val warning: (String) -> Unit = {},
    private val transport: ((String) -> String)? = null
) {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private var cachedKey: Pair<String, Long>? = null

    fun getVideoData(bvId: String): JsonObject {
        var failure: Exception? = null
        val sources = listOf<() -> JsonObject>(
            { apiData(signedUrl("/x/web-interface/wbi/view", mapOf("bvid" to bvId))) },
            { apiData("$API/x/web-interface/view?bvid=$bvId") },
            { videoDataFromHtml(request("https://www.bilibili.com/video/$bvId")) }
        )
        for (source in sources) {
            try {
                return validateVideoData(source(), bvId)
            } catch (e: Exception) {
                failure = e
                warning("视频详情备用解析: ${e.message}")
            }
        }
        throw IOException("视频详情接口和网页解析均失败: ${failure?.message}", failure)
    }

    /** Request a single MP4 containing both audio and video; DASH/FLV need a muxer. */
    fun getPlaybackUrls(bvId: String, cid: Long, quality: String): List<String> {
        var failure: Exception? = null
        for (qn in listOf(quality, "16").distinct()) {
            val params = mapOf(
                "bvid" to bvId, "cid" to cid.toString(), "qn" to qn,
                "fnval" to "1", "fnver" to "0", "platform" to "html5"
            )
            val sources = listOf<() -> String>(
                { signedUrl("/x/player/wbi/playurl", params) },
                { "$API/x/player/playurl?${WbiSigner.query(params)}" }
            )
            for (source in sources) {
                try {
                    val data = apiData(source())
                    val segments = data.get("durl")?.takeIf { it.isJsonArray }?.asJsonArray
                    if (data.get("format")?.asString?.endsWith("mp4") != true || segments?.size() != 1) {
                        throw IOException("播放接口未返回完整的单文件 MP4 (qn=$qn)")
                    }
                    val segment = segments[0].asJsonObject
                    val urls = mutableListOf<String>()
                    segment.get("url")?.takeUnless { it.isJsonNull }?.asString?.let(urls::add)
                    segment.get("backup_url")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach {
                        if (it.isJsonPrimitive) urls.add(it.asString)
                    }
                    val validUrls = urls.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
                    if (validUrls.isEmpty()) throw IOException("播放接口返回的 MP4 地址为空")
                    return validUrls
                } catch (e: Exception) {
                    failure = e
                    warning("视频播放备用解析: ${e.message}")
                }
            }
        }
        throw IOException("无法获取可下载的 MP4: ${failure?.message}", failure)
    }

    @Synchronized
    private fun signedUrl(path: String, params: Map<String, String>): String {
        val now = System.currentTimeMillis()
        val cached = cachedKey
        val key = if (cached != null && now - cached.second < 300_000) {
            cached.first
        } else {
            // The nav response has code=-101 for guests, but still supplies wbi_img.
            val nav = JsonParser.parseString(request("$API/x/web-interface/nav")).asJsonObject
            val images = nav.getAsJsonObject("data")?.getAsJsonObject("wbi_img")
                ?: throw IOException("导航接口未返回 WBI 密钥 (code=${nav.get("code")})")
            val imgKey = images["img_url"].asString.substringAfterLast('/').substringBefore('.')
            val subKey = images["sub_url"].asString.substringAfterLast('/').substringBefore('.')
            WbiSigner.mixinKey(imgKey, subKey).also { cachedKey = it to now }
        }
        return "$API$path?${WbiSigner.sign(params, key, now / 1000)}"
    }

    private fun apiData(url: String): JsonObject {
        val json = JsonParser.parseString(request(url)).asJsonObject
        val code = json.get("code")?.asInt ?: throw IOException("接口响应缺少 code")
        if (code != 0) {
            if (code == -403 || code == -352) cachedKey = null
            throw IOException("B站接口 code=$code: ${json.get("message")?.asString ?: "未知错误"}")
        }
        val data = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("接口未返回 data")
        if (data.has("v_voucher")) {
            cachedKey = null
            throw IOException("B站接口要求风控验证 (v_voucher)")
        }
        return data
    }

    private fun request(url: String): String {
        transport?.let { return it(url) }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            val uri = URI(url)
            synchronized(cookies) {
                cookies.get(uri, emptyMap()).forEach { (name, values) ->
                    connection.setRequestProperty(name, values.joinToString("; "))
                }
            }
            cookie().trim().takeIf { it.isNotEmpty() }?.let { connection.setRequestProperty("Cookie", it) }
            val status = connection.responseCode
            synchronized(cookies) { cookies.put(uri, connection.headerFields.filterKeys { it != null }) }
            if (status != HttpURLConnection.HTTP_OK) {
                val reason = if (status == 412 || status == 429) "，请求被风控拦截，可在配置中填写 bilibiliCookie 后重试" else ""
                throw IOException("HTTP $status (${connection.url.host}${connection.url.path})$reason")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val API = "https://api.bilibili.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        internal fun videoDataFromHtml(html: String): JsonObject {
            val marker = Regex("(?:window\\.)?__INITIAL_STATE__\\s*=\\s*").find(html)
                ?: throw IOException("视频网页没有 __INITIAL_STATE__ 数据")
            // Read one JSON value, ignoring the JavaScript following the assignment.
            val state = JsonReader(StringReader(html.substring(marker.range.last + 1))).use {
                JsonParser.parseReader(it).asJsonObject
            }
            return state.getAsJsonObject("videoData")
                ?: throw IOException("视频网页没有 videoData 数据")
        }

        private fun validateVideoData(data: JsonObject, bvId: String): JsonObject {
            if (data.get("bvid")?.asString != bvId || data.get("title")?.asString.isNullOrBlank() ||
                data.get("cid")?.asLong?.let { it > 0 } != true ||
                data.get("duration")?.asInt?.let { it >= 0 } != true ||
                data.getAsJsonObject("owner")?.get("name")?.asString == null ||
                data.get("stat")?.isJsonObject != true || data.get("pic")?.isJsonPrimitive != true) {
                throw IOException("视频详情不完整或视频 ID 不匹配")
            }
            if (!data.has("desc") || data["desc"].isJsonNull) data.addProperty("desc", "")
            return data
        }
    }
}

internal object WbiSigner {
    private val mixinTable = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
    )

    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        require(raw.length == 64) { "WBI 密钥长度无效" }
        return mixinTable.map { raw[it] }.joinToString("")
    }

    fun query(params: Map<String, String>): String = params.toSortedMap().entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
    }

    fun sign(params: Map<String, String>, key: String, timestamp: Long): String {
        val values = (params - setOf("w_rid", "wts") + ("wts" to timestamp.toString())).mapValues { (_, value) ->
            value.filterNot { it in "!'()*" }
        }
        val query = query(values)
        val signature = MessageDigest.getInstance("MD5").digest((query + key).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$query&w_rid=$signature"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
}
