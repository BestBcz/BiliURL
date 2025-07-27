package com.bcz.bilivideoparser

import com.google.gson.Gson
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
        val uid: String,
        val userName: String,
        val content: String,
        val pictures: List<String>,
        val timestamp: Long
    )

    
    //统一入口：支持t.bilibili.com、bilibili.com/opus、b23.tv短链
    
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
            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
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

    // 支持 t.bilibili.com/xxx、www.bilibili.com/opus/xxx、b23.tv/xxx 跳转
    
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

    //发送动态消息到群聊
    
    suspend fun sendDynamicMessage(group: Group, result: BiliDynamicResult) {
        val sb = StringBuilder()
        sb.appendLine("【B站动态】")
        sb.appendLine("作者: ${result.userName} (UID: ${result.uid})")
        sb.appendLine("内容: ${result.content}")

        // 先发送文字消息
        group.sendMessage(sb.toString())

        // 然后发送所有图片
        if (result.pictures.isNotEmpty()) {
            for (picUrl in result.pictures) {
                try {
                    val imageFile = downloadThumbnail(picUrl)
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
                    // 使用logger记录错误，但不在群聊中显示
                    println("动态图片发送失败: ${e.message}")
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
                    val tempFile = File.createTempFile("bili_dynamic_", ".jpg")
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