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
        
        // 存储从HTML获取的标题
        var htmlTitle: String? = null
        
        // 从QQ小程序消息中提取标题（如果可用）
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
        
        // 尝试多个API接口 - 根据bilibili-API-collect官方文档
        val apis = listOf(
            "https://www.bilibili.com/opus",                   // 新增：直接获取opus页面HTML源码（最优先）
            "https://api.bilibili.com/x/dynamic/feed/detail",  // 新版动态详情API
            "https://api.bilibili.com/x/dynamic/detail",       // 通用动态详情API
            "https://api.bilibili.com/x/dynamic/opus/view",    // opus动态专用API
            "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/get_dynamic_detail"  // 旧版动态API
        )
        
        for (apiUrl in apis) {
            try {
                val fullUrl = if (apiUrl.contains("feed/detail")) {
                    "$apiUrl?id=$dynamicId&features=itemOpusStyle"
                } else if (apiUrl.contains("opus/view")) {
                    "$apiUrl?id=$dynamicId"  // opus API使用id参数
                } else if (apiUrl.contains("x/dynamic/detail")) {
                    "$apiUrl?dynamic_id=$dynamicId"  // 通用动态详情API
                } else if (apiUrl.contains("www.bilibili.com/opus")) {
                    "$apiUrl/$dynamicId"  // opus页面使用 /opus/dynamicId 格式
                } else {
                    "$apiUrl?dynamic_id=$dynamicId"  // 旧版API使用dynamic_id参数
                }
                
                val connection = URL(fullUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                connection.setRequestProperty("Referer", "https://www.bilibili.com/")
                connection.setRequestProperty("Origin", "https://www.bilibili.com")
                
                // 读取配置中的 Cookie
                val cookie = try { Config.bilibiliCookie } catch (_: Exception) { "" }
                if (!cookie.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookie)
                }
                
                // 检查响应码
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    println("[BiliDynamicParser] API $apiUrl 返回错误码: $responseCode")
                    continue
                }
                
                val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
                val response = reader.readText()
                reader.close()

                println("[BiliDynamicParser] 请求: $fullUrl")
                println("[BiliDynamicParser] 返回: $response")

                // 如果是HTML页面，直接解析HTML
                if (apiUrl.contains("www.bilibili.com/opus")) {
                    val opusHtml = response
                    
                    // 从HTML中提取标题
                    val titleFromHtml = Regex("""<span class="opus-module-title__text">([^<]+)</span>""").find(opusHtml)?.groupValues?.get(1)
                    
                    // 如果成功获取到标题，继续使用其他API获取正文和图片
                    if (!titleFromHtml.isNullOrBlank()) {
                        // 保存标题，继续循环使用其他API获取完整信息
                        // 这里不直接返回，而是继续尝试其他API
                        println("[BiliDynamicParser] HTML解析成功，标题: $titleFromHtml")
                        htmlTitle = titleFromHtml // 保存HTML标题
                        // 继续循环，尝试其他API获取正文和图片
                        continue
                    }
                    
                    continue
                }

                // 对于API接口，尝试JSON解析
                val json = try {
                    Gson().fromJson(response, Map::class.java)
                } catch (e: Exception) {
                    println("[BiliDynamicParser] JSON解析异常: ${e.message}")
                    continue
                }
                val data = json["data"] as? Map<*, *> ?: continue
                
                // 尝试解析新API格式
                if (apiUrl.contains("feed/detail")) {
                    val item = data["item"] as? Map<*, *> ?: continue
                    val modules = item["modules"] as? Map<*, *> ?: continue
                    val moduleAuthor = modules["module_author"] as? Map<*, *> ?: continue
                    val moduleDynamic = modules["module_dynamic"] as? Map<*, *> ?: continue
                    
                    // 获取用户信息
                    val userName = moduleAuthor["name"] as? String ?: ""
                    val uid = moduleAuthor["mid"]?.toString() ?: ""
                    val timestamp = (item["time"] as? Double)?.toLong() ?: 0L
                    
                    // 严格按照成功插件的逻辑获取内容
                    val content = when (item["type"]) {
                        2 -> { // DYNAMIC_TYPE_DRAW 图文动态
                            val major = moduleDynamic["major"] as? Map<*, *>
                            val opus = major?.get("opus") as? Map<*, *>
                            val title = opus?.get("title") as? String ?: ""
                            val summary = (opus?.get("summary") as? Map<*, *>)?.get("text") as? String ?: ""
                            
                            if (!title.isNullOrBlank()) {
                                "$title\n$summary"
                            } else {
                                moduleDynamic["desc"]?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            }
                        }
                        64 -> { // DYNAMIC_TYPE_OPUS 新版图文动态（opus）
                            val major = moduleDynamic["major"] as? Map<*, *>
                            val opus = major?.get("opus") as? Map<*, *>
                            
                            // 严格按照成功插件的逻辑：优先使用opus.title，其次使用opus.summary.text
                            val title = opus?.get("title") as? String ?: ""
                            val summary = (opus?.get("summary") as? Map<*, *>)?.get("text") as? String ?: ""
                            
                            if (!title.isNullOrBlank()) {
                                if (!summary.isNullOrBlank()) {
                                    "$title\n$summary"
                                } else {
                                    title
                                }
                            } else {
                                summary ?: moduleDynamic["desc"]?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            }
                        }
                        8 -> { // DYNAMIC_TYPE_WORD 文字动态
                            moduleDynamic["desc"]?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                        }
                        else -> { // 其他类型
                            moduleDynamic["desc"]?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                        }
                    }
                    
                    // 获取图片列表 - 严格按照成功插件的逻辑
                    val pictures = mutableListOf<String>()
                    val major = moduleDynamic["major"] as? Map<*, *>
                    
                    when (item["type"]) {
                        2 -> { // 图文动态
                            val draw = major?.get("draw") as? Map<*, *>
                            val items = draw?.get("items") as? List<*>
                            items?.forEach { picItem ->
                                val src = (picItem as? Map<*, *>)?.get("src") as? String
                                if (src != null) pictures.add(src)
                            }
                        }
                        64 -> { // 新版图文动态（opus）- 严格按照成功插件的逻辑
                            val opus = major?.get("opus") as? Map<*, *>
                            val picturesList = opus?.get("pics") as? List<*>
                            
                            // 从opus.pics获取图片 - 根据成功插件的Dynamic.kt定义
                            picturesList?.forEach { pic ->
                                val picItem = pic as? Map<*, *>
                                val src = picItem?.get("src") as? String
                                if (src != null) pictures.add(src)
                            }
                            
                            // 如果没有pics，尝试从draw获取（兼容性）
                            if (pictures.isEmpty()) {
                                val draw = major?.get("draw") as? Map<*, *>
                                val items = draw?.get("items") as? List<*>
                                items?.forEach { picItem ->
                                    val src = (picItem as? Map<*, *>)?.get("src") as? String
                                    if (src != null) pictures.add(src)
                                }
                            }
                        }
                        8 -> { // 文字动态
                            // 不处理图片
                        }
                        else -> { // 其他类型
                            // 根据具体类型处理
                        }
                    }

                    return BiliDynamicResult(
                        dynamicId = dynamicId,
                        uid = uid,
                        userName = userName,
                        content = content,
                        pictures = pictures,
                        timestamp = timestamp
                    )
                } else if (apiUrl.contains("opus/view")) {
                    // 专门解析opus API的响应 - 根据HTML源码中的数据结构
                    val opusData = data["opus"] as? Map<*, *> ?: continue
                    val userInfo = data["user"] as? Map<*, *> ?: continue
                    
                    // 获取用户信息
                    val userName = userInfo["name"] as? String ?: ""
                    val uid = userInfo["uid"]?.toString() ?: ""
                    val timestamp = (opusData["ctime"] as? Double)?.toLong() ?: 0L
                    
                    // 获取opus内容
                    val title = opusData["title"] as? String ?: ""
                    val summary = opusData["summary"] as? String ?: ""
                    
                    val content = if (!title.isNullOrBlank()) {
                        if (!summary.isNullOrBlank()) {
                            "$title\n$summary"
                        } else {
                            title
                        }
                    } else {
                        summary ?: ""
                    }
                    
                    // 获取图片列表
                    val pictures = mutableListOf<String>()
                    val pics = opusData["pics"] as? List<*>
                    pics?.forEach { pic ->
                        val picItem = pic as? Map<*, *>
                        val src = picItem?.get("src") as? String
                        if (src != null) pictures.add(src)
                    }
                    
                    return BiliDynamicResult(
                        dynamicId = dynamicId,
                        uid = uid,
                        userName = userName,
                        content = content,
                        pictures = pictures,
                        timestamp = timestamp
                    )
                } else if (apiUrl.contains("x/dynamic/detail")) {
                    // 解析通用动态详情API的响应
                    val dynamicData = data["dynamic"] as? Map<*, *> ?: continue
                    val userInfo = data["user"] as? Map<*, *> ?: continue
                    
                    // 获取用户信息
                    val userName = userInfo["name"] as? String ?: ""
                    val uid = userInfo["uid"]?.toString() ?: ""
                    val timestamp = (dynamicData["timestamp"] as? Double)?.toLong() ?: 0L
                    
                    // 获取动态内容
                    val title = dynamicData["title"] as? String ?: ""
                    val content = dynamicData["content"] as? String ?: ""
                    val summary = dynamicData["summary"] as? String ?: ""
                    
                    val finalContent = if (!title.isNullOrBlank()) {
                        if (!content.isNullOrBlank()) {
                            "$title\n$content"
                        } else if (!summary.isNullOrBlank()) {
                            "$title\n$summary"
                        } else {
                            title
                        }
                    } else if (!content.isNullOrBlank()) {
                        content
                    } else {
                        summary ?: ""
                    }
                    
                    // 获取图片列表
                    val pictures = mutableListOf<String>()
                    val pics = dynamicData["pics"] as? List<*>
                    pics?.forEach { pic ->
                        val picItem = pic as? Map<*, *>
                        val src = picItem?.get("src") as? String ?: picItem?.get("url") as? String
                        if (src != null) pictures.add(src)
                    }
                    
                    return BiliDynamicResult(
                        dynamicId = dynamicId,
                        uid = uid,
                        userName = userName,
                        content = finalContent,
                        pictures = pictures,
                        timestamp = timestamp
                    )

                } else {
                    // 尝试解析旧API格式
                    val card = data["card"] as? Map<*, *> ?: continue
                    val cardStr = card["card"] as? String ?: continue
                    val cardObj = Gson().fromJson(cardStr, Map::class.java)
                    
                    val item = cardObj["item"] as? Map<*, *> ?: continue
                    val user = cardObj["user"] as? Map<*, *> ?: continue
                    val uid = user["uid"]?.toString() ?: ""
                    val userName = user["name"] as? String ?: ""
                    val description = item["description"] as? String ?: ""
                    
                    // 尝试从多个字段获取标题信息
                    var title = ""
                    var content = ""
                    
                    // 1. 尝试从item.title获取
                    title = item["title"] as? String ?: ""
                    
                    // 2. 尝试从item.text获取（某些动态可能有这个字段）
                    if (title.isBlank()) {
                        title = item["text"] as? String ?: ""
                    }
                    
                    // 3. 尝试从item.content获取
                    if (title.isBlank()) {
                        title = item["content"] as? String ?: ""
                    }
                    
                    // 4. 尝试从extend_json中提取更多信息
                    if (title.isBlank()) {
                        val extendJson = card["extend_json"] as? String
                        if (!extendJson.isNullOrBlank()) {
                            try {
                                val extendObj = Gson().fromJson(extendJson, Map::class.java)
                                // 尝试从extend_json中获取标题相关信息
                                title = extendObj["title"] as? String ?: ""
                                if (title.isBlank()) {
                                    title = extendObj["text"] as? String ?: ""
                                }
                            } catch (_: Exception) {
                                // 忽略JSON解析错误
                            }
                        }
                    }
                    
                    // 5. 尝试从QQ小程序的meta信息中获取标题（如果是从mirai:app来的）
                    if (title.isBlank()) {
                        // 使用从QQ小程序消息中提取的标题
                        title = qqTitle
                    }
                    
                    // 6. 尝试从B站API的其他字段中提取标题
                    if (title.isBlank()) {
                        // 尝试从item的其他可能字段获取
                        title = item["name"] as? String ?: ""
                        if (title.isBlank()) {
                            title = item["subtitle"] as? String ?: ""
                        }
                        if (title.isBlank()) {
                            title = item["headline"] as? String ?: ""
                        }
                    }
                    
                    // 7. 智能分析description，提取可能的标题
                    if (title.isBlank() && !description.isBlank()) {
                        // 分析description，寻找可能的标题模式
                        val lines = description.split("\n")
                        if (lines.isNotEmpty()) {
                            val firstLine = lines[0].trim()
                            
                            // 判断第一行是否像标题（长度适中，不以标点符号结尾）
                            if (firstLine.length in 5..100 && 
                                !firstLine.endsWith("。") && 
                                !firstLine.endsWith("！") && 
                                !firstLine.endsWith("？") &&
                                !firstLine.endsWith(".") &&
                                !firstLine.endsWith("!") &&
                                !firstLine.endsWith("?")) {
                                
                                title = firstLine
                                // 剩余内容作为描述
                                if (lines.size > 1) {
                                    content = lines.drop(1).joinToString("\n").trim()
                                }
                            } else {
                                // 如果第一行不像标题，尝试从整个描述中提取关键词
                                val words = description.split("，", "。", "！", "？", ",", ".", "!", "?")
                                for (word in words) {
                                    val trimmed = word.trim()
                                    if (trimmed.length in 5..50 && 
                                        !trimmed.contains("http") && 
                                        !trimmed.contains("www") &&
                                        !trimmed.contains("@") &&
                                        !trimmed.contains("#")) {
                                        title = trimmed
                                        break
                                    }
                                }
                            }
                        }
                    }
                    
                    // 8. 如果都没有标题，尝试从description中提取第一行作为标题
                    if (title.isBlank() && !description.isBlank()) {
                        val lines = description.split("\n")
                        if (lines.isNotEmpty()) {
                            title = lines[0].trim()
                            // 如果第一行太长，截取前50个字符
                            if (title.length > 50) {
                                title = title.substring(0, 50) + "..."
                            }
                            // 剩余内容作为描述
                            if (lines.size > 1) {
                                content = lines.drop(1).joinToString("\n").trim()
                            }
                        }
                    }
                    
                    // 最终内容组合 - 优先使用HTML标题，格式化显示
                    val finalContent = if (htmlTitle != null && htmlTitle.isNotBlank()) {
                        // 使用HTML标题，格式化显示
                        if (content.isNotBlank()) {
                            "标题：$htmlTitle\n内容：$content"
                        } else if (description != htmlTitle) {
                            "标题：$htmlTitle\n内容：$description"
                        } else {
                            "标题：$htmlTitle"
                        }
                    } else if (title.isNotBlank()) {
                        // 使用API提取的标题
                        if (content.isNotBlank()) {
                            "标题：$title\n内容：$content"
                        } else if (description != title) {
                            "标题：$title\n内容：$description"
                        } else {
                            "标题：$title"
                        }
                    } else {
                        // 没有标题，只显示内容
                        description
                    }
                    
                    val pictures = mutableListOf<String>()
                    val picturesList = item["pictures"] as? List<*>
                    picturesList?.forEach { pic ->
                        val urlPic = (pic as? Map<*, *>)?.get("img_src") as? String
                        if (urlPic != null) pictures.add(urlPic)
                    }
                    
                    val timestamp = (item["upload_time"] as? Double)?.toLong() ?: 0L
                    
                    return BiliDynamicResult(
                        dynamicId = dynamicId,
                        uid = uid,
                        userName = userName,
                        content = finalContent,
                        pictures = pictures,
                        timestamp = timestamp
                    )
                }
                
            } catch (e: Exception) {
                println("[BiliDynamicParser] API $apiUrl 异常: ${e.message}")
                continue
            }
        }
        
        println("[BiliDynamicParser] 所有API都失败了")
        return null
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
        sb.appendLine("${result.content}")
        
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