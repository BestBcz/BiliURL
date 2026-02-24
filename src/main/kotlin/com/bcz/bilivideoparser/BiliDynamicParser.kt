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

    fun parseDynamic(url: String, qqAppMessage: String? = null): BiliDynamicResult? {
        val dynamicId = extractDynamicIdFromAnyUrl(url) ?: return null

        var qqTitle = ""
        if (!qqAppMessage.isNullOrBlank()) {
            try {
                val qqJson = Gson().fromJson(qqAppMessage, Map::class.java)
                val meta = qqJson["meta"] as? Map<*, *>
                val news = meta?.get("news") as? Map<*, *>
                qqTitle = news?.get("title") as? String ?: ""
            } catch (_: Exception) {
                // 忽略JSON解析错误
            }
        }

        val apis = listOf(
            "https://www.bilibili.com/opus", // 方案一 (HTML + SSR JSON)
            "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/get_dynamic_detail", // 方案二 (旧版 API)
            "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail" // 方案三 (新版 API)
        )

        for (apiUrl in apis) {
            try {
                val fullUrl = if (apiUrl.contains("www.bilibili.com/opus")) {
                    "$apiUrl/$dynamicId" // 方案一
                } else if (apiUrl.contains("v1/dynamic_svr")) {
                    "$apiUrl?dynamic_id=$dynamicId" // 方案二
                } else if (apiUrl.contains("v1/detail")) {
                    "$apiUrl?id=$dynamicId" // 方案三
                } else {
                    "$apiUrl?dynamic_id=$dynamicId" // 兜底
                }

                val connection = URL(fullUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/5.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/5.36")
                connection.setRequestProperty("Referer", "https://www.bilibili.com/")
                connection.setRequestProperty("Origin", "https://www.bilibili.com") //


                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    BiliVideoParser.logger.warning("[DynamicParser] API $apiUrl 返回错误码: $responseCode")
                    continue // 尝试下一个 API
                }

                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
                connection.disconnect()

                BiliVideoParser.logger.info("[DynamicParser] 请求: $fullUrl")
                BiliVideoParser.logger.info("[DynamicParser] 返回: $response")

                // 方案一: HTML + SSR JSON 解析 (使用更强健的 Regex) ---
                if (apiUrl.contains("www.bilibili.com/opus")) {
                    // 抓取 window.__INITIAL_STATE__
                    val stateRegex = Regex("""window\.__INITIAL_STATE__=(\{.*\});\(function\(\)""")
                    val match = stateRegex.find(response)

                    if (match != null && match.groupValues.size > 1) {
                        val jsonStr = match.groupValues[1]
                        try {
                            val json = Gson().fromJson(jsonStr, Map::class.java)

                            // 开始解析这个 JSON
                            val data = json["detail"] as? Map<*, *>
                            if (data != null) {
                                BiliVideoParser.logger.info("[DynamicParser] 方案一(HTML/JSON)解析成功...")
                                val result = parseV1Detail(dynamicId, data)
                                if (result != null) return result
                            }
                        } catch (e: Exception) {
                            BiliVideoParser.logger.warning("[DynamicParser] 方案一(HTML/JSON) JSON 转换失败: ${e.message}")
                        }
                    }
                    // 如果 Regex 失败，继续尝试下一个 API
                    continue
                }

                val json = try {
                    Gson().fromJson(response, Map::class.java)
                } catch (e: Exception) {
                    BiliVideoParser.logger.warning("[DynamicParser] JSON解析异常: ${e.message}")
                    continue
                }
                val data = json["data"] as? Map<*, *> ?: continue

                // 新版 v1/detail API
                if (apiUrl.contains("v1/detail")) { //
                    BiliVideoParser.logger.info("[DynamicParser] 正在尝试 方案三 (v1/detail)...")
                    val result = parseV1Detail(dynamicId, data) // [修改] 抽离为独立函数
                    if (result != null) return result

                    // 旧版 v1/dynamic_svr API
                } else if (apiUrl.contains("v1/dynamic_svr")) { //
                    BiliVideoParser.logger.info("[DynamicParser] 正在尝试 方案二 (v1/dynamic_svr)...")
                    val card = data["card"] as? Map<*, *> ?: continue

                    val desc = card["desc"] as? Map<*, *> ?: continue
                    val type = (desc["type"] as? Double)?.toInt()

                    val userProfile = desc["user_profile"] as? Map<*, *>
                    val userInfo = userProfile?.get("info") as? Map<*, *>
                    val uid = userInfo?.get("uid")?.toString() ?: ""
                    val userName = userInfo?.get("uname") as? String ?: ""
                    val timestamp = (desc["timestamp"] as? Double)?.toLong() ?: 0L

                    // 检查是否为 Type 8
                    if (type == 8) {
                        val cardStr = card["card"] as? String ?: continue
                        val cardObj = Gson().fromJson(cardStr, Map::class.java)

                        val dynamicText = cardObj["dynamic"] as? String ?: ""
                        val videoTitle = cardObj["title"] as? String ?: ""
                        val videoPic = cardObj["pic"] as? String ?: ""

                        val content = if (dynamicText.isNotBlank()) "$dynamicText\n\n视频投稿：$videoTitle" else "投稿了视频：$videoTitle"
                        val pictures = if (videoPic.isNotBlank()) listOf(videoPic) else emptyList()

                        return BiliDynamicResult(
                            dynamicId = dynamicId,
                            uid = uid,
                            userName = userName,
                            content = content,
                            pictures = pictures,
                            timestamp = timestamp
                        )
                    }

                    // type=64 常见于旧版专栏卡片（如 opus_fallback 场景）
                    if (type == 64) {
                        val cardStr = card["card"] as? String ?: continue
                        val cardObj = Gson().fromJson(cardStr, Map::class.java)

                        val articleTitle = cardObj["title"] as? String ?: ""
                        val articleSummary = cardObj["summary"] as? String ?: ""
                        val finalContent = when {
                            articleTitle.isNotBlank() && articleSummary.isNotBlank() -> "标题：$articleTitle\n内容：$articleSummary"
                            articleTitle.isNotBlank() -> "标题：$articleTitle"
                            articleSummary.isNotBlank() -> articleSummary
                            else -> ""
                        }

                        val pictures = mutableListOf<String>()
                        (cardObj["origin_image_urls"] as? List<*>)
                            ?.mapNotNull { it as? String }
                            ?.forEach { pictures.add(it) }

                        return BiliDynamicResult(
                            dynamicId = dynamicId,
                            uid = uid,
                            userName = userName,
                            content = finalContent,
                            pictures = pictures,
                            timestamp = timestamp
                        )
                    }

                    //  处理其他类型的动态 (如纯文本/图片)
                    val cardStr = card["card"] as? String ?: continue
                    val cardObj = Gson().fromJson(cardStr, Map::class.java)
                    val item = cardObj["item"] as? Map<*, *> ?: continue // 旧逻辑依赖 "item"

                    val uid_legacy = uid
                    val userName_legacy = userName

                    val description = item["description"] as? String ?: ""
                    var title = ""
                    var content = ""
                    title = item["title"] as? String ?: ""
                    if (title.isBlank()) title = item["text"] as? String ?: ""
                    if (title.isBlank()) title = item["content"] as? String ?: ""
                    if (title.isBlank()) {
                        val extendJson = card["extend_json"] as? String
                        if (!extendJson.isNullOrBlank()) {
                            try {
                                val extendObj = Gson().fromJson(extendJson, Map::class.java)
                                title = extendObj["title"] as? String ?: ""
                                if (title.isBlank()) title = extendObj["text"] as? String ?: ""
                            } catch (_: Exception) {}
                        }
                    } //
                    if (title.isBlank()) title = qqTitle
                    if (title.isBlank()) {
                        title = item["name"] as? String ?: ""
                        if (title.isBlank()) title = item["subtitle"] as? String ?: ""
                        if (title.isBlank()) title = item["headline"] as? String ?: ""
                    } //
                    if (title.isBlank() && !description.isBlank()) {
                        val lines = description.split("\n")
                        if (lines.isNotEmpty()) {
                            val firstLine = lines[0].trim()
                            if (firstLine.length in 5..100 && !firstLine.endsWith("。") && !firstLine.endsWith("！") && !firstLine.endsWith("？") && !firstLine.endsWith(".") && !firstLine.endsWith("!") && !firstLine.endsWith("?")) {
                                title = firstLine
                                if (lines.size > 1) content = lines.drop(1).joinToString("\n").trim()
                            } else {
                                val words = description.split("，", "。", "！", "？", ",", ".", "!", "?")
                                for (word in words) {
                                    val trimmed = word.trim()
                                    if (trimmed.length in 5..50 && !trimmed.contains("http") && !trimmed.contains("www") && !trimmed.contains("@") && !trimmed.contains("#")) {
                                        title = trimmed
                                        break
                                    }
                                }
                            }
                        }
                    } //
                    if (title.isBlank() && !description.isBlank()) {
                        val lines = description.split("\n")
                        if (lines.isNotEmpty()) {
                            title = lines[0].trim()
                            if (title.length > 50) title = title.substring(0, 50) + "..."
                            if (lines.size > 1) content = lines.drop(1).joinToString("\n").trim()
                        }
                    } //

                    val finalContent = if (title.isNotBlank()) {
                        if (content.isNotBlank()) "标题：$title\n内容：$content" else if (description != title) "标题：$title\n内容：$description" else "标题：$title"
                    } else {
                        description
                    } //

                    val pictures = mutableListOf<String>()
                    val picturesList = item["pictures"] as? List<*>
                    picturesList?.forEach { pic ->
                        val urlPic = (pic as? Map<*, *>)?.get("img_src") as? String
                        if (urlPic != null) pictures.add(urlPic)
                    }
                    val timestamp_legacy = (item["upload_time"] as? Double)?.toLong() ?: timestamp

                    return BiliDynamicResult(dynamicId = dynamicId, uid = uid_legacy, userName = userName_legacy, content = finalContent, pictures = pictures, timestamp = timestamp_legacy)
                }

            } catch (e: Exception) {
                BiliVideoParser.logger.warning("[DynamicParser] API $apiUrl 异常: ${e.message}")
                continue
            }
        }

        BiliVideoParser.logger.error("[DynamicParser] 所有API都失败了")
        return null
    }

    // v1/detail 解析逻辑，同时服务于方案一
    private fun parseV1Detail(dynamicId: String, data: Map<*, *>): BiliDynamicResult? {
        try {
            val item = data["item"] as? Map<*, *> ?: return null
            val modules = item["modules"] as? Map<*, *> ?: return null
            val moduleAuthor = modules["module_author"] as? Map<*, *> ?: return null
            val moduleDynamic = modules["module_dynamic"] as? Map<*, *> ?: return null

            val userName = moduleAuthor["name"] as? String ?: ""
            val uid = moduleAuthor["mid"]?.toString() ?: ""
            val timestamp = (moduleAuthor["pub_ts"] as? Double)?.toLong() ?: ((item["time"] as? Double)?.toLong() ?: 0L)

            val desc = moduleDynamic["desc"] as? Map<*, *>
            val major = moduleDynamic["major"] as? Map<*, *>

            var content = desc?.get("text") as? String ?: ""
            val pictures = mutableListOf<String>()

            if (major != null) {
                if (major.containsKey("opus")) {
                    val opus = major["opus"] as? Map<*, *>
                    val title = opus?.get("title") as? String ?: ""
                    val summary = (opus?.get("summary") as? Map<*, *>)?.get("text") as? String ?: ""
                    content = if (title.isNotBlank()) "$title\n$summary" else if (summary.isNotBlank()) summary else content

                    val pics = opus?.get("pics") as? List<*>
                    pics?.forEach { pic ->
                        val picItem = pic as? Map<*, *>
                        val src = (picItem?.get("url") ?: picItem?.get("src")) as? String
                        if (src != null) pictures.add(src)
                    }
                } else if (major.containsKey("draw")) {
                    val draw = major["draw"] as? Map<*, *>
                    val items = draw?.get("items") as? List<*>
                    items?.forEach { picItem ->
                        val src = (picItem as? Map<*, *>)?.get("src") as? String
                        if (src != null) pictures.add(src)
                    }
                } else if (major.containsKey("archive")) { // “投稿了视频”
                    val archive = major["archive"] as? Map<*, *>
                    val title = archive?.get("title") as? String ?: ""
                    val cover = archive?.get("cover") as? String ?: ""
                    val bvid = archive?.get("bvid") as? String ?: ""
                    val text = "投稿了视频：$title"
                    content = if (content.isBlank()) text else "$content\n\n$text"
                    if (cover.isNotBlank()) pictures.add(cover)
                    if (bvid.isNotBlank()) content += "\nhttps://www.bilibili.com/video/$bvid"
                }
            }

            return BiliDynamicResult(dynamicId = dynamicId, uid = uid, userName = userName, content = content.trim(), pictures = pictures, timestamp = timestamp)
        } catch (e: Exception) {
            BiliVideoParser.logger.error("[DynamicParser] V1 (方案一/三) 解析异常: ${e.message}")
            return null
        }
    }

    // 支持 t.bilibili.com/xxx、www.bilibili.com/opus/xxx、b23.tv/xxx 跳转
    fun extractDynamicIdFromAnyUrl(url: String): String? {
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
        Regex("""t\.bilibili\.com/(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""bilibili\.com/opus/(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    //(保留) 发送动态消息到群聊
    suspend fun sendDynamicMessage(group: Group, result: BiliDynamicResult) {
        val sb = StringBuilder()
        sb.appendLine("【B站动态】")
        sb.appendLine("作者: ${result.userName} ")
        sb.appendLine("${result.content}")

        group.sendMessage(sb.toString())

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
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/5.36")
                connection.setRequestProperty("Referer", "https://www.bilibili.com/")

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // 统一使用 DOWNLOAD_DIR
                    val tempFile = File(BiliVideoParser.DOWNLOAD_DIR, "dynamic_${url.hashCode()}.jpg") //
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
