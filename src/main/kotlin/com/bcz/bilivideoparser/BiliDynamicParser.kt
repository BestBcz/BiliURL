package com.bcz.bilivideoparser

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * B站动态解析工具
 * 支持解析 https://t.bilibili.com/xxxxxxx、https://www.bilibili.com/opus/xxxxxxx、b23.tv短链
 */
object BiliDynamicParser {
    data class BiliDynamicResult(
        val dynamicId: String,
        val uid: String,
        val userName: String,
        val content: String,
        val pictures: List<String>,
        val timestamp: Long
    )

    /**
     * 统一入口：支持t.bilibili.com、bilibili.com/opus、b23.tv短链
     */
    fun parseDynamic(url: String): BiliDynamicResult? {
        val dynamicId = extractDynamicIdFromAnyUrl(url) ?: return null
        val apiUrl = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/get_dynamic_detail?dynamic_id=$dynamicId"
        try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.setRequestProperty("Origin", "https://www.bilibili.com")
            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            val response = reader.readText()
            reader.close()
            println("[BiliDynamicParser] 请求: $apiUrl")
            println("[BiliDynamicParser] 返回: $response")
            val json = Gson().fromJson(response, Map::class.java)
            val data = json["data"] as? Map<*, *>
            if (data == null) {
                println("[BiliDynamicParser] data为null，原始内容: $response")
                return null
            }
            val card = data["card"] as? Map<*, *>
            val cardStr = card?.get("card") as? String
            val cardObj = if (cardStr != null) Gson().fromJson(cardStr, Map::class.java) else null
            val item = cardObj?.get("item") as? Map<*, *>
            val user = cardObj?.get("user") as? Map<*, *>
            val userName = user?.get("name") as? String ?: ""
            val uid = user?.get("uid")?.toString() ?: ""
            val content = item?.get("description") as? String ?: ""
            val pictures = mutableListOf<String>()
            val picturesList = item?.get("pictures") as? List<*>
            if (picturesList != null) {
                for (pic in picturesList) {
                    val url = (pic as? Map<*, *>)?.get("img_src") as? String
                    if (url != null) pictures.add(url)
                }
            }
            val timestamp = (item?.get("upload_time") as? Double)?.toLong() ?: 0L
            return BiliDynamicResult(
                dynamicId = dynamicId,
                uid = uid,
                userName = userName,
                content = content,
                pictures = pictures,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            println("[BiliDynamicParser] 异常: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * 支持 t.bilibili.com/xxx、www.bilibili.com/opus/xxx、b23.tv/xxx 跳转
     */
    fun extractDynamicIdFromAnyUrl(url: String): String? {
        // 1. 处理b23.tv短链跳转
        if (url.contains("b23.tv/")) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connect()
                val location = conn.getHeaderField("Location") ?: return null
                return extractDynamicIdFromAnyUrl(location)
            } catch (_: Exception) {
                return null
            }
        }
        // 2. t.bilibili.com/xxx
        Regex("""t\.bilibili\.com/(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        // 3. www.bilibili.com/opus/xxx
        Regex("""bilibili\.com/opus/(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }
} 