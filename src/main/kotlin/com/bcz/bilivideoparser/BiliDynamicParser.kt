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
import kotlinx.serialization.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull


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
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            val response = reader.readText()
            reader.close()

            println("[BiliDynamicParser] 请求: $apiUrl")
            println("[BiliDynamicParser] 返回: $response")

            val json = Gson().fromJson(response, Map::class.java)
            val data = json["data"] as? Map<*, *> ?: return null
            val card = data["card"] as? Map<*, *>
            val cardStr = card?.get("card") as? String ?: return null
            val cardObj = Gson().fromJson(cardStr, Map::class.java)

            val item = cardObj["item"] as? Map<*, *>
            val user = cardObj["user"] as? Map<*, *>
            val uid = user?.get("uid")?.toString() ?: ""
            val userName = user?.get("name") as? String ?: ""
            val description = item?.get("description") as? String ?: ""

            // 旧 API 标题
            val oldTitle = item?.get("title") as? String
            var content: String? = if (!oldTitle.isNullOrBlank()) {
                "$oldTitle\n$description"
            } else {
                description
            }

            val pictures = mutableListOf<String>()
            val picturesList = item?.get("pictures") as? List<*>
            picturesList?.forEach { pic ->
                val urlPic = (pic as? Map<*, *>)?.get("img_src") as? String
                if (urlPic != null) pictures.add(urlPic)
            }

            val timestamp = (item?.get("upload_time") as? Double)?.toLong() ?: 0L

            // 缺标题 → 新 API 尝试
            if (oldTitle.isNullOrBlank()) {
                val newContent = tryParseFromNewApi(dynamicId)
                if (!newContent.isNullOrBlank()) {
                    content = newContent
                }
            }

            // 旧 API、新 API 都没标题 → HTML 兜底
            if (content.isNullOrBlank() || !content.contains("\n")) {
                val htmlTitle = fetchTitleFromHtml(dynamicId)
                if (!htmlTitle.isNullOrBlank() && (content == null || !content.startsWith(htmlTitle))) {
                    content = if (content.isNullOrBlank()) {
                        htmlTitle
                    } else {
                        htmlTitle + "\n" + content
                    }
                }
            }

            return BiliDynamicResult(
                dynamicId = dynamicId,
                uid = uid,
                userName = userName,
                content = content ?: "",
                pictures = pictures,
                timestamp = timestamp
            )

        } catch (e: Exception) {
            println("[BiliDynamicParser] 异常: ${e.message}")
            e.printStackTrace()
            return null
        }
    }


    private fun fetchTitleFromHtml(opusId: String): String? {
        val url = "https://www.bilibili.com/opus/$opusId"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (Config.bilibiliCookie.isNotBlank()) {
                conn.setRequestProperty("Cookie", Config.bilibiliCookie)
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // 用正则匹配 og:title
            val match = Regex("<meta\\s+property=\"og:title\"\\s+content=\"(.*?)\"").find(html)
            match?.groupValues?.getOrNull(1)?.trim()
        } catch (e: Exception) {
            println("[BiliDynamicParser] HTML 获取标题失败: ${e.message}")
            null
        }
    }



    private fun fetchOpusTitle(opusId: String): String? {
        val url = "https://api.bilibili.com/x/dynamic/opus/view?opus_id=$opusId"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (Config.bilibiliCookie.isNotBlank()) {
                conn.setRequestProperty("Cookie", Config.bilibiliCookie)
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = Gson().fromJson(text, Map::class.java)
            val data = json["data"] as? Map<*, *>
            val item = data?.get("item") as? Map<*, *>
            return item?.get("title") as? String
        } catch (e: Exception) {
            println("[BiliDynamicParser] 获取 opus 标题失败: ${e.message}")
            null
        }
    }



    // —— 1) 兜底：通过 opus/view 拿标题（支持带 Cookie）——
    fun getOpusTitle(opusId: String, cookie: String? = null): String? {
        val url = "https://api.bilibili.com/x/dynamic/opus/view?opus_id=$opusId"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val root = Json.parseToJsonElement(reader.readText()).jsonObject
                val code = root["code"]?.jsonPrimitive?.int ?: -1
                if (code == 0) {
                    root["data"]?.jsonObject
                        ?.get("item")?.jsonObject
                        ?.get("title")?.jsonPrimitive?.contentOrNull
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }


    //新版动态api尝试获取标题（旧api无法获取）
    // —— 2) 新接口优先，必要时再兜底到 opus/view ——
// 返回 "title\nsummary" 或仅 summary；都没有则返回 null
    fun tryParseFromNewApi(dynamicId: String): String? {
        val newApiUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=$dynamicId"
        return try {
            val conn = URL(newApiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Referer", "https://www.bilibili.com/")
            val cookie = Config.bilibiliCookie
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)

            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            println("[BiliDynamicParser] 新 API 请求: $newApiUrl")
            println("[BiliDynamicParser] 新 API 返回: $response")

            @Suppress("UNCHECKED_CAST")
            val root = Gson().fromJson(response, Map::class.java) as Map<String, Any?>
            val code = (root["code"] as? Number)?.toInt() ?: 0
            if (code == -352) {
                // 鉴权风控，直接走兜底
                return getOpusTitle(dynamicId, cookie)
            }

            val data = root["data"] as? Map<*, *> ?: return null
            val item = data["item"] as? Map<*, *> ?: return null
            val modules = item["modules"] as? Map<*, *> ?: return null
            val moduleDynamic = modules["module_dynamic"] as? Map<*, *> ?: return null
            val major = moduleDynamic["major"] as? Map<*, *>

            var title: String? = null
            var summary: String? = null

            if (major != null) {
                if (major.containsKey("opus")) {
                    val opus = major["opus"] as? Map<*, *>
                    title = opus?.get("title") as? String
                    summary = opus?.get("summary") as? String
                } else if (major.containsKey("draw")) {
                    val draw = major["draw"] as? Map<*, *>
                    title = draw?.get("title") as? String // 很多时候没有

                    // 没有 summary 就用 desc.text
                    val desc = (moduleDynamic["desc"] as? Map<*, *>)?.get("text") as? String
                    summary = summary ?: desc

                    // ✅ 新增：无论如何都尝试用 opus/view 接口补标题
                    val opusTitle = fetchOpusTitle(dynamicId)
                    if (!opusTitle.isNullOrBlank()) {
                        title = opusTitle
                    }
                }
            } else {
                // 有些动态 major 直接为 null，只能用 desc.text
                val desc = (moduleDynamic["desc"] as? Map<*, *>)?.get("text") as? String
                summary = summary ?: desc
            }

            // 如果还是没有标题，但判断出是图文/opus，就走兜底接口补标题
            if (title.isNullOrBlank()) {
                // opus_id 和 dynamicId 一致时可直接复用；实际观测基本一致
                title = getOpusTitle(dynamicId, cookie)
            }
            if (title.isNullOrBlank()) {
                title = fetchTitleFromHtml(dynamicId)
            }
            return when {
                !title.isNullOrBlank() -> title + "\n" + (summary ?: "")
                !summary.isNullOrBlank() -> summary
                else -> null
            }
        } catch (e: Exception) {
            println("[BiliDynamicParser] 新 API 异常: ${e.message}")
            null
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