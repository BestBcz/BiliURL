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

    // 恢复为多 API 轮询, 并移除 Cookie
    fun parseDynamic(url: String, qqAppMessage: String? = null): BiliDynamicResult? {
        val dynamicId = extractDynamicIdFromAnyUrl(url) ?: return null

        var htmlTitle: String? = null

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
            "https://www.bilibili.com/opus",
            "https://api.bilibili.com/x/dynamic/feed/detail",
            "https://api.bilibili.com/x/dynamic/detail",
            "https://api.bilibili.com/x/dynamic/opus/view",
            "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/get_dynamic_detail"
        ) //

        for (apiUrl in apis) {
            try {
                val fullUrl = if (apiUrl.contains("feed/detail")) {
                    "$apiUrl?id=$dynamicId&features=itemOpusStyle"
                } else if (apiUrl.contains("opus/view")) {
                    "$apiUrl?id=$dynamicId"
                } else if (apiUrl.contains("x/dynamic/detail")) {
                    "$apiUrl?dynamic_id=$dynamicId"
                } else if (apiUrl.contains("www.bilibili.com/opus")) {
                    "$apiUrl/$dynamicId"
                } else {
                    "$apiUrl?dynamic_id=$dynamicId"
                } //

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
                    continue
                }

                val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
                val response = reader.readText()
                reader.close()

                BiliVideoParser.logger.info("[DynamicParser] 请求: $fullUrl")
                BiliVideoParser.logger.info("[DynamicParser] 返回: $response")

                if (apiUrl.contains("www.bilibili.com/opus")) {
                    val opusHtml = response
                    val titleFromHtml = Regex("""<span class="opus-module-title__text">([^<]+)</span>""").find(opusHtml)?.groupValues?.get(1)

                    if (!titleFromHtml.isNullOrBlank()) {
                        BiliVideoParser.logger.info("[DynamicParser] HTML解析成功，标题: $titleFromHtml")
                        htmlTitle = titleFromHtml
                        continue
                    }

                    continue
                } //

                val json = try {
                    Gson().fromJson(response, Map::class.java)
                } catch (e: Exception) {
                    BiliVideoParser.logger.warning("[DynamicParser] JSON解析异常: ${e.message}")
                    continue
                }
                val data = json["data"] as? Map<*, *> ?: continue

                if (apiUrl.contains("feed/detail")) {
                    val item = data["item"] as? Map<*, *> ?: continue
                    val modules = item["modules"] as? Map<*, *> ?: continue
                    val moduleAuthor = modules["module_author"] as? Map<*, *> ?: continue
                    val moduleDynamic = modules["module_dynamic"] as? Map<*, *> ?: continue

                    val userName = moduleAuthor["name"] as? String ?: ""
                    val uid = moduleAuthor["mid"]?.toString() ?: ""
                    val timestamp = (item["time"] as? Double)?.toLong() ?: 0L

                    val content = when (item["type"]) {
                        2 -> {
                            val major = moduleDynamic["major"] as? Map<*, *>
                            val opus = major?.get("opus") as? Map<*, *>
                            val title = opus?.get("title") as? String ?: ""
                            val summary = (opus?.get("summary") as? Map<*, *>)?.get("text") as? String ?: ""
                            if (!title.isNullOrBlank()) "$title\n$summary" else moduleDynamic["desc"]?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                        }
                        64 -> {
                            val major = moduleDynamic["major"] as? Map<*, *>
                            val opus = major?.get("opus") as? Map<*, *>
                            val title = opus?.get("title") as? String ?: ""
                            val summary = (opus?.get("summary") as? Map<*, *>)?.get("text") as? String ?: ""
                            if (!title.isNullOrBlank()) (if (!summary.isNullOrBlank()) "$title\n$summary" else title) else (summary ?: moduleDynamic["desc"]?.let { (it as? Map<*, *>)?.get("text") as? String } ?: "")
                        }
                        8 -> moduleDynamic["desc"]?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                        else -> moduleDynamic["desc"]?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                    } //

                    val pictures = mutableListOf<String>()
                    val major = moduleDynamic["major"] as? Map<*, *>

                    when (item["type"]) {
                        2 -> {
                            val draw = major?.get("draw") as? Map<*, *>
                            val items = draw?.get("items") as? List<*>
                            items?.forEach { picItem ->
                                val src = (picItem as? Map<*, *>)?.get("src") as? String
                                if (src != null) pictures.add(src)
                            }
                        }
                        64 -> {
                            val opus = major?.get("opus") as? Map<*, *>
                            val picturesList = opus?.get("pics") as? List<*>
                            picturesList?.forEach { pic ->
                                val picItem = pic as? Map<*, *>
                                val src = picItem?.get("src") as? String
                                if (src != null) pictures.add(src)
                            }
                            if (pictures.isEmpty()) {
                                val draw = major?.get("draw") as? Map<*, *>
                                val items = draw?.get("items") as? List<*>
                                items?.forEach { picItem ->
                                    val src = (picItem as? Map<*, *>)?.get("src") as? String
                                    if (src != null) pictures.add(src)
                                }
                            }
                        }
                    } //

                    return BiliDynamicResult(dynamicId = dynamicId, uid = uid, userName = userName, content = content, pictures = pictures, timestamp = timestamp)

                } else if (apiUrl.contains("opus/view")) {
                    val opusData = data["opus"] as? Map<*, *> ?: continue
                    val userInfo = data["user"] as? Map<*, *> ?: continue
                    val userName = userInfo["name"] as? String ?: ""
                    val uid = userInfo["uid"]?.toString() ?: ""
                    val timestamp = (opusData["ctime"] as? Double)?.toLong() ?: 0L
                    val title = opusData["title"] as? String ?: ""
                    val summary = opusData["summary"] as? String ?: ""
                    val content = if (!title.isNullOrBlank()) (if (!summary.isNullOrBlank()) "$title\n$summary" else title) else (summary ?: "")
                    val pictures = mutableListOf<String>()
                    val pics = opusData["pics"] as? List<*>
                    pics?.forEach { pic ->
                        val picItem = pic as? Map<*, *>
                        val src = picItem?.get("src") as? String
                        if (src != null) pictures.add(src)
                    }
                    return BiliDynamicResult(dynamicId = dynamicId, uid = uid, userName = userName, content = content, pictures = pictures, timestamp = timestamp)

                } else if (apiUrl.contains("x/dynamic/detail")) {
                    val dynamicData = data["dynamic"] as? Map<*, *> ?: continue
                    val userInfo = data["user"] as? Map<*, *> ?: continue
                    val userName = userInfo["name"] as? String ?: ""
                    val uid = userInfo["uid"]?.toString() ?: ""
                    val timestamp = (dynamicData["timestamp"] as? Double)?.toLong() ?: 0L
                    val title = dynamicData["title"] as? String ?: ""
                    val content = dynamicData["content"] as? String ?: ""
                    val summary = dynamicData["summary"] as? String ?: ""
                    val finalContent = if (!title.isNullOrBlank()) (if (!content.isNullOrBlank()) "$title\n$content" else if (!summary.isNullOrBlank()) "$title\n$summary" else title) else if (!content.isNullOrBlank()) content else (summary ?: "")
                    val pictures = mutableListOf<String>()
                    val pics = dynamicData["pics"] as? List<*>
                    pics?.forEach { pic ->
                        val picItem = pic as? Map<*, *>
                        val src = picItem?.get("src") as? String ?: picItem?.get("url") as? String
                        if (src != null) pictures.add(src)
                    }
                    return BiliDynamicResult(dynamicId = dynamicId, uid = uid, userName = userName, content = finalContent, pictures = pictures, timestamp = timestamp)

                } else {
                    //
                    val card = data["card"] as? Map<*, *> ?: continue

                    // 1. 获取 desc 来判断类型
                    val desc = card["desc"] as? Map<*, *> ?: continue
                    val type = (desc["type"] as? Double)?.toInt()

                    // 2. 从 desc 中获取用户信息
                    val userProfile = desc["user_profile"] as? Map<*, *>
                    val userInfo = userProfile?.get("info") as? Map<*, *>
                    val uid = userInfo?.get("uid")?.toString() ?: ""
                    val userName = userInfo?.get("uname") as? String ?: ""
                    val timestamp = (desc["timestamp"] as? Double)?.toLong() ?: 0L

                    // 3. 检查是否为 Type 8 (投稿视频)
                    if (type == 8) {
                        val cardStr = card["card"] as? String ?: continue
                        val cardObj = Gson().fromJson(cardStr, Map::class.java)

                        val dynamicText = cardObj["dynamic"] as? String ?: ""
                        val videoTitle = cardObj["title"] as? String ?: ""
                        val videoPic = cardObj["pic"] as? String ?: ""

                        // 组合动态文本和视频标题
                        val content = "$dynamicText\n\n视频投稿：$videoTitle"
                        // 使用视频封面作为动态图片
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

                    // (保留旧逻辑) 处理其他类型的动态 (如纯文本/图片)
                    val cardStr = card["card"] as? String ?: continue
                    val cardObj = Gson().fromJson(cardStr, Map::class.java)
                    val item = cardObj["item"] as? Map<*, *> ?: continue // 旧逻辑依赖 "item"

                    // [注意] 旧逻辑的用户信息获取方式可能不准确，但我们先保留它
                    val user = cardObj["user"] as? Map<*, *> ?: continue
                    val uid_legacy = user["uid"]?.toString() ?: uid
                    val userName_legacy = user["name"] as? String ?: userName

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

                    val finalContent = if (htmlTitle != null && htmlTitle.isNotBlank()) {
                        if (content.isNotBlank()) "标题：$htmlTitle\n内容：$content" else if (description != htmlTitle) "标题：$htmlTitle\n内容：$description" else "标题：$htmlTitle"
                    } else if (title.isNotBlank()) {
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

    //发送动态消息到群聊
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