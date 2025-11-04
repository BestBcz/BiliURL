package com.bcz.bilivideoparser

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object BiliDynamicParser {
    data class BiliDynamicResult(
        val dynamicId: String,
        val uid: String, // API 不返回, 将为 "N/A"
        val userName: String,
        val content: String,
        val pictures: List<String>,
        val timestamp: Long
    )

    // 使用第三方 API
    fun parseDynamic(url: String, qqAppMessage: String? = null): BiliDynamicResult? {
        val dynamicId = extractDynamicIdFromAnyUrl(url) ?: return null

        // B 站动态的 URL 结构有两种: t.bilibili.com 和 www.bilibili.com/opus
        val dynamicUrl = "https://t.bilibili.com/$dynamicId"
        val apiUrl = "http://api.xingzhige.cn/API/b_parse/?url=${java.net.URLEncoder.encode(dynamicUrl, "UTF-8")}"

        BiliVideoParser.logger.info("[DynamicParser] 正在使用第三方 API 解析: $dynamicUrl")

        try {
            //调用 API
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                BiliVideoParser.logger.error("[DynamicParser] API $apiUrl 返回错误码: ${connection.responseCode}")
                return null
            }

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()

            val json = JsonParser.parseString(response).asJsonObject

            // 解析响应
            if (json.has("code") && json["code"].asInt == 0 && json.has("data")) {
                val data = json["data"].asJsonObject
                val msgType = json["msg"]?.asString

                // API 文档说动态返回 "dynamic" 或 "opus"
                if (msgType == "dynamic" || msgType == "opus") {

                    // 映射到 BiliDynamicResult
                    val userName = data["author"]?.asString ?: "N/A"
                    val title = data["title"]?.asString ?: ""
                    val desc = data["desc"]?.asString ?: ""

                    val content = if (title.isNotBlank()) "$title\n$desc" else desc

                    val timestamp = data["timestamp"]?.asLong ?: (System.currentTimeMillis() / 1000)

                    val pictures = mutableListOf<String>()
                    data["images"]?.asJsonArray?.forEach {
                        pictures.add(it.asString)
                    }

                    return BiliDynamicResult(
                        dynamicId = dynamicId,
                        uid = "N/A (API不支持)", // API 不返回 uid
                        userName = userName,
                        content = content.trim(),
                        pictures = pictures,
                        timestamp = timestamp
                    )
                }
            }

            BiliVideoParser.logger.error("[DynamicParser] API 解析失败: ${json["msg"]?.asString ?: "未知错误"}")
            return null

        } catch (e: Exception) {
            BiliVideoParser.logger.error("[DynamicParser] API $apiUrl 异常: ${e.message}")
            return null
        }
    }

    // 支持 t.bilibili.com/xxx、www.bilibili.com/opus/xxx、b23.tv/xxx 跳转
    fun extractDynamicIdFromAnyUrl(url: String): String? {
        // 处理b23.tv短链跳转
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
        // t.bilibili.com/xxx
        Regex("""t\.bilibili\.com/(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        // www.bilibili.com/opus/xxx
        Regex("""bilibili\.com/opus/(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    //发送动态消息到群聊
    suspend fun sendDynamicMessage(group: Group, result: BiliDynamicResult) {
        val sb = StringBuilder()
        sb.appendLine("【B站动态】")
        sb.appendLine("作者: ${result.userName} ")
        sb.appendLine("${result.content}")

        // 先发送文字消息
        group.sendMessage(sb.toString())

        // 然后发送所有图片
        if (result.pictures.isNotEmpty()) {
            for (picUrl in result.pictures) {
                try {
                    val imageFile = downloadThumbnail(picUrl) // (保留) 复用下载函数
                    if (imageFile != null) {
                        val imageResource = imageFile.toExternalResource("jpg")
                        try {
                            val imageMessage = group.uploadImage(imageResource)
                            group.sendMessage(imageMessage)
                        } finally {
                            withContext(Dispatchers.IO) {
                                imageResource.close()
                            }
                            withContext(Dispatchers.IO) {
                                delay(1000)
                                imageFile.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    BiliVideoParser.logger.warning("动态图片发送失败: ${e.message}")
                }
            }
        }
    }

    //下载缩略图
    private suspend fun downloadThumbnail(url: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                connection.setRequestProperty("Referer", "https://www.bilibili.com/")

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val tempFile = File(BiliVideoParser.DOWNLOAD_DIR, "dynamic_${url.hashCode()}.jpg")
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}