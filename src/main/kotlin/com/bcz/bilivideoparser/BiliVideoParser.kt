package com.bcz.bilivideoparser

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.async
import net.mamoe.mirai.console.command.CommandManager
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Paths
import javax.imageio.ImageIO
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap



object BiliVideoParser : KotlinPlugin(
    JvmPluginDescription(
        id = "com.bcz.bilivideoparser",
        name = "BiliVideoParser",
        version = "2.3.0"
    ) {
        author("Bcz")
    }
) {

    // 冷却缓存
    private val parseCache = ConcurrentHashMap<String, Long>()

    // 检查是否处于冷却中的函数
    private fun isRateLimited(id: String, type: String): Boolean {
        //  30 秒冷却
        val cooldownMillis = 30_000L

        val currentTime = System.currentTimeMillis()
        val lastParsedTime = parseCache[id]

        if (lastParsedTime != null && (currentTime - lastParsedTime) < cooldownMillis) {
            logger.info("[$type] ID: $id 仍在 30 秒解析冷却中，已跳过。")
            return true // 处于冷却中
        }

        // 更新时间戳并允许解析
        parseCache[id] = currentTime
        return false
    }


    val DOWNLOAD_DIR = Paths.get("bilidownload").toFile().apply {
        if (!exists()) mkdirs()
    }
    private fun cleanupOldFiles() {
        val files = DOWNLOAD_DIR.listFiles()
        files?.forEach {
            if (it.exists() && it.isFile) {
                if (!it.delete()) {
                    logger.warning("无法删除文件: ${it.name}")
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startAutoCleanupJob() {
        GlobalScope.launch {
            while (true) {
                try {
                    cleanupOldFiles()
                    logger.info("定时清理已执行")
                } catch (e: Exception) {
                    logger.error("定时清理任务异常: ${e.message}")
                }
                delay(24 * 60 * 60 * 1000) // 每24小时执行一次
            }
        }
    }

    private data class VideoReference(val bvId: String, val page: Int = 1) {
        val rateLimitKey: String
            get() = if (page > 1) "$bvId:p$page" else bvId

        fun longUrl(): String {
            return "https://www.bilibili.com/video/$bvId" + if (page > 1) "?p=$page" else ""
        }
    }

    private fun resolveRedirectUrl(url: String): String? {
        return try {
            var currentUrl = url
            repeat(5) {
                val connection = URL(currentUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                connection.connect()
                val location = connection.getHeaderField("Location")
                connection.disconnect()

                if (location.isNullOrBlank()) {
                    return currentUrl
                }

                currentUrl = URL(URL(currentUrl), location).toString()
                if (!currentUrl.contains("b23.tv/")) {
                    return currentUrl
                }
            }
            currentUrl
        } catch (e: Exception) {
            logger.warning("解析短链接跳转失败: ${e.message}")
            null
        }
    }

    private fun extractPageNumberFromUrl(url: String): Int {
        val normalized = url
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        return Regex("""[?&]p=(\d+)""")
            .find(normalized)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
    }

    private fun extractVideoReferenceFromUrl(url: String): VideoReference? {
        return try {
            val normalized = url.replace("\\/", "/")
            val bvIdRegex = Regex("""BV[0-9A-Za-z]+""")
            val directMatch = bvIdRegex.find(normalized)
            if (directMatch != null) {
                return VideoReference(directMatch.value, extractPageNumberFromUrl(normalized))
            }

            if (normalized.contains("b23.tv/")) {
                val shortUrl = extractBilibiliUrlFromText(normalized) ?: normalized
                val realUrl = resolveRedirectUrl(shortUrl) ?: return null
                val bvIdMatch = bvIdRegex.find(realUrl) ?: return null
                val resolvedPage = extractPageNumberFromUrl(realUrl)
                val shortUrlPage = extractPageNumberFromUrl(shortUrl)
                return VideoReference(
                    bvId = bvIdMatch.value,
                    page = if (resolvedPage > 1) resolvedPage else shortUrlPage
                )
            }
            null
        } catch (e: Exception) {
            logger.warning("提取视频ID失败: ${e.message}")
            null
        }
    }

    data class VideoDetails(
        val title: String,
        val desc: String,
        val owner: Owner,
        val stat: Stat,
        val pic: String, // 封面图 URL
        val duration: Int, // 视频时长（秒）
        val cid: Long, // 默认分P的 cid
        val pages: List<Page>? // 分P列表，用于按 p 参数选择对应 cid
    ) {
        data class Page(
            val cid: Long,
            val page: Int,
            val part: String?,
            val duration: Int
        )

        data class Owner(val name: String)
        data class Stat(
            val view: Int,
            val danmaku: Int,
            val reply: Int,
            val favorite: Int,
            val coin: Int,
            val share: Int,
            val like: Int
        )
    }

    data class BiliApiResponse(val code: Int, val data: VideoDetails?)
    private fun getVideoDetails(bvId: String): VideoDetails? {
        val apiUrl = "https://api.bilibili.com/x/web-interface/view?bvid=$bvId"
        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            val response = reader.readText()
            reader.close()
            val apiResponse = Gson().fromJson(response, BiliApiResponse::class.java)
            if (apiResponse.code == 0) apiResponse.data else null
        } catch (e: Exception) {
            logger.error("获取视频详情失败: ${e.message}")
            null
        }
    }

    private fun VideoDetails.selectedPage(pageNumber: Int): VideoDetails.Page? {
        val pageList = pages.orEmpty()
        return pageList.firstOrNull { it.page == pageNumber } ?: pageList.getOrNull(pageNumber - 1)
    }

    private fun VideoDetails.selectedCid(pageNumber: Int): Long {
        return selectedPage(pageNumber)?.cid ?: cid
    }

    private fun VideoDetails.selectedDuration(pageNumber: Int): Int {
        return selectedPage(pageNumber)?.duration ?: duration
    }

    private fun VideoDetails.selectedTitle(pageNumber: Int): String {
        val page = selectedPage(pageNumber)
        val part = page?.part?.takeIf { it.isNotBlank() }
        return if (part != null && pages.orEmpty().size > 1) {
            "$title - P${page.page} $part"
        } else {
            title
        }
    }

    private fun VideoDetails.pageCount(): Int {
        return pages?.size ?: 1
    }


    private fun downloadThumbnail(url: String): File? {
        val rawImageFile = File(DOWNLOAD_DIR, "raw_thumbnail_${url.hashCode()}.img")
        val jpgFile = File(DOWNLOAD_DIR, "thumbnail_${url.hashCode()}.jpg")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connection.connect()

            if (connection.responseCode != 200) {
                logger.error("封面图下载失败: HTTP ${connection.responseCode}")
                return null
            }

            connection.inputStream.use { input ->
                rawImageFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val originalImage = ImageIO.read(rawImageFile)
            if (originalImage == null) {
                logger.error("无法解码封面图")
                rawImageFile.delete()
                return null
            }

            val rgbImage = BufferedImage(originalImage.width, originalImage.height, BufferedImage.TYPE_INT_RGB)
            val graphics = rgbImage.createGraphics()
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, originalImage.width, originalImage.height)
            graphics.drawImage(originalImage, 0, 0, null)
            graphics.dispose()

            val success = ImageIO.write(rgbImage, "jpg", jpgFile)
            if (!success) {
                logger.error("封面图转JPG失败")
                rawImageFile.delete()
                jpgFile.delete()
                return null
            }

            rawImageFile.delete()
            return jpgFile
        } catch (e: Exception) {
            logger.error("封面图处理失败: ${e.message}")
            rawImageFile.delete()
            jpgFile.delete()
            return null
        }
    }

    private fun generateDefaultThumbnail(): File {
        val defaultThumb = File(DOWNLOAD_DIR, "default_thumbnail.jpg")
        if (!defaultThumb.exists()) {
            try {
                val width = 1656
                val height = 931
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                graphics.color = Color.BLACK
                graphics.fillRect(0, 0, width, height)
                graphics.dispose()

                ImageIO.write(image, "jpg", defaultThumb)
            } catch (e: Exception) {
                logger.error("生成默认缩略图失败: ${e.message}")
            }
        }
        return defaultThumb
    }

    private suspend fun sendShortVideoMessage(group: Group, videoFile: File, thumbnailUrl: String? = null) {
        if (!videoFile.exists()) {
            logger.warning("视频文件不存在: ${videoFile.name}")
            return
        }

        var thumbnailFile: File? = null
        var videoResource: net.mamoe.mirai.utils.ExternalResource? = null
        var thumbnailResource: net.mamoe.mirai.utils.ExternalResource? = null

        try {
            val thumbnailJob = async(Dispatchers.IO) {
                if (thumbnailUrl != null) {
                    try {
                        downloadThumbnail(thumbnailUrl)
                    } catch (e: Exception) {
                        logger.warning("封面图下载失败: ${e.message}")
                        null
                    }
                } else null
            }

            val videoResourceJob = async(Dispatchers.IO) {
                try {
                    videoFile.toExternalResource("mp4")
                } catch (e: Exception) {
                    logger.error("视频资源准备失败: ${e.message}")
                    null
                }
            }

            thumbnailFile = thumbnailJob.await()
            val thumbnailToUse = thumbnailFile ?: generateDefaultThumbnail()
            thumbnailResource = thumbnailToUse.toExternalResource("jpg")
            videoResource = videoResourceJob.await()

            if (videoResource == null) {
                logger.error("视频资源准备失败")
                return
            }

            if (Config.enableThumbnail && thumbnailFile != null) {
                try {
                    withTimeout(5000) {
                        val imageMessage = group.uploadImage(thumbnailResource)
                        group.sendMessage(imageMessage)
                    }
                } catch (e: Exception) {
                    logger.warning("封面图发送失败，继续发送视频: ${e.message}")
                }
            }

            try {
                withTimeout(30000) {
                    val shortVideo = group.uploadShortVideo(thumbnailResource, videoResource, videoFile.name)
                    group.sendMessage(shortVideo)
                    logger.info("✅ 视频发送成功: ${videoFile.name}")
                }
            } catch (e: Exception) {
                if (e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("Timed out", ignoreCase = true) == true) {
                    logger.warning("视频发送超时，但可能已成功发送: ${e.message}")
                } else {
                    logger.error("视频发送失败: ${e.message}")
                }
            }

        } catch (e: Exception) {
            logger.error("视频发送过程异常: ${e.message}")
        } finally {
            withContext(Dispatchers.IO) {
                try {
                    videoResource?.close()
                } catch (e: Exception) {
                    logger.warning("关闭视频资源失败: ${e.message}")
                }
                try {
                    thumbnailResource?.close()
                } catch (e: Exception) {
                    logger.warning("关闭缩略图资源失败: ${e.message}")
                }
            }

            withContext(Dispatchers.IO) {
                delay(2000)
                try {
                    videoFile.delete()
                    thumbnailFile?.delete()
                } catch (e: Exception) {
                    logger.warning("删除临时文件失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun GroupMessageEvent.handleMiniAppMessage() {
        val jsonData = Gson().fromJson(message.content, MiniAppJsonData::class.java)
        if (jsonData.app == "com.tencent.miniapp_01" &&
            jsonData.meta.detail_1.appid == "1109937557") {

            val shortUrl = jsonData.meta.detail_1.qqdocurl
            if (handleBilibiliUrl(shortUrl, message.content)) {
                return
            }
        }

        val fallbackUrl = extractBilibiliUrlFromText(message.content)
        if (!fallbackUrl.isNullOrBlank()) {
            handleBilibiliUrl(fallbackUrl, message.content)
        }
    }

    private suspend fun GroupMessageEvent.handleBilibiliUrl(url: String, qqAppJson: String? = null): Boolean {
        val dynamicId = BiliDynamicParser.extractDynamicIdFromAnyUrl(url)
        if (dynamicId != null) {
            if (isRateLimited(dynamicId, "Dynamic")) return true
            val dynamicResult = BiliDynamicParser.parseDynamic(url, qqAppJson)
            if (dynamicResult != null) {
                BiliDynamicParser.sendDynamicMessage(group, dynamicResult)
                return true
            }
        }

        val articleId = BiliArticleParser.extractArticleIdFromAnyUrl(url)
        if (articleId != null) {
            if (isRateLimited("cv$articleId", "Article")) return true
            val articleResult = BiliArticleParser.parseArticle(url, qqAppJson)
            if (articleResult != null) {
                sendArticleMessage(group, articleResult)
                return true
            }
            group.sendMessage("❌ 解析专栏失败或专栏不存在")
            return true
        }

        val video = extractVideoReferenceFromUrl(url)
        if (video != null) {
            val originalLink = extractBilibiliUrlFromText(url) ?: url
            val videoLink = if (Config.useShortLink) originalLink else video.longUrl()
            handleParsedBVId(group, video, videoLink, sender.id)
            return true
        }

        return false
    }

    suspend fun sendArticleMessage(group: Group, result: BiliArticleParser.BiliArticleResult) {
        val sb = StringBuilder()
        sb.appendLine("【B站专栏】")
        sb.appendLine("作者: ${result.authorName}")
        sb.appendLine("标题：${result.title}")
        if (result.summary.isNotBlank()) {
            sb.appendLine("内容：${result.summary}")
        }
        sb.appendLine(result.jumpUrl)
        group.sendMessage(sb.toString().trim())
    }

    private fun extractBilibiliUrlFromText(text: String): String? {
        val normalized = text.replace("\\/", "/")
        val regex = Regex("""https?://(?:www\.)?(?:bilibili\.com|b23\.tv)/[^\s\"]+""")
        return regex.find(normalized)?.value
    }

    private suspend fun GroupMessageEvent.handleLinkMessage(shortUrl: String) {
        val video = extractVideoReferenceFromUrl(shortUrl)
        if (video == null) {
            logger.warning("短链接未解析到有效 BV 号: $shortUrl")
            return
        }
        val videoLink = if (Config.useShortLink) shortUrl else video.longUrl()


        handleParsedBVId(group, video, videoLink, sender.id)
    }

    // 切换到方案B (HtmlUnit) + 方案A (injahow 备用)
    private suspend fun proceedToDownload(group: Group, video: VideoReference, details: VideoDetails?) {
        val tempVideoFile = File(DOWNLOAD_DIR, "downloaded_video_${video.bvId}_p${video.page}_api.mp4")
        var success = false
        var videoUrl: String?

        // --- 方案 B: 原生 API
        val cid = details?.selectedCid(video.page)
        if (cid != null) {
            try {
                // 在 IO 线程中执行网络请求
                videoUrl = withContext(Dispatchers.IO) {
                    downloadWithNativeApi(video, cid, Config.videoQuality)
                }

                if (videoUrl != null) {
                    logger.info("✅ [方案B] API 解析成功，正在下载视频...")
                    withContext(Dispatchers.IO) {
                        downloadVideoFile(videoUrl!!, tempVideoFile, video.longUrl())
                    }
                    success = true
                } else {
                    logger.warning("⚠️ [方案B] 原生 API 未返回 .mp4 链接 (可能需要DASH/FFmpeg 或该画质不可用)。")
                }
            } catch (e: Exception) {
                logger.error("❌ [方案B] 原生 API (HtmlUnit) 失败: ${e.message}")
                // 捕获异常，继续执行方案 A
            }
        } else {
            logger.warning("⚠️ [方案B] 跳过：未能获取到视频 CID。")
        }


        // --- 方案 A: 第三方 API (备用) ---
        if (!success) {
            if (video.page > 1) {
                logger.warning("⚠️ [方案A] 分P视频跳过备用 API，避免备用接口忽略 p 参数后误下载 P1。")
            } else {
                logger.info("🚀 [方案A] 方案B失败，正在启动备用 API  解析...")
                try {
                    videoUrl = withContext(Dispatchers.IO) {
                        downloadWithFallbackApi(video.bvId, Config.videoQuality)
                    }

                    if (videoUrl != null) {
                        logger.info("✅ [方案A] API 解析成功，正在下载视频...")
                        withContext(Dispatchers.IO) {
                            downloadVideoFile(videoUrl, tempVideoFile, video.longUrl())
                        }
                        success = true
                    } else {
                        logger.error("❌ [方案A] 备用 API 解析失败: 未找到 video.url")
                    }
                } catch (e: Exception) {
                    logger.error("❌ [方案A] 备用 API 失败: ${e.message}")
                }
            }
        }

        // 最终处理
        if (success && tempVideoFile.exists() && tempVideoFile.length() > 0) {
            val fileSizeMB = tempVideoFile.length() / (1024 * 1024)
            logger.info("✅ 视频下载完成 (${fileSizeMB}MB)，正在发送...")
            sendShortVideoMessage(group, tempVideoFile, details?.pic)
        } else {
            logger.error("❌ 所有下载方案均失败。")
            group.sendMessage("❌ 视频下载失败 (所有方案均已尝试)。")
        }
    }

    // 方案B的实现
    private fun downloadWithNativeApi(video: VideoReference, cid: Long, quality: String): String? {
        // fnval=0 请求 mp4/flv (durl) 格式
        val apiUrl = "https://api.bilibili.com/x/player/playurl?bvid=${video.bvId}&cid=$cid&qn=$quality&fnval=0"

        try {
            // 使用 HttpURLConnection
            logger.info("[方案B] 正在请求原生 API (Header伪装): $apiUrl")

            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            // 伪装 User-Agent 和 Referer
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connection.setRequestProperty("Referer", video.longUrl())

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.error("[方案B] API 解析失败: HTTP ${connection.responseCode}")
                if (connection.responseCode == 412) {
                    logger.error("❌ [方案B] 伪装 Header 测试失败，服务器返回 412 (Precondition Failed)！")
                }
                return null
            }

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()

            if (response.isBlank()) {
                logger.error("[方案B] API 返回为空")
                return null
            }

            val json = JsonParser.parseString(response).asJsonObject

            if (json.has("code") && json["code"].asInt == 0) {
                val data = json["data"]?.asJsonObject ?: return null

                // 查找 durl (MP4)
                if (data.has("durl") && data["durl"].asJsonArray.size() > 0) {
                    val durl = data["durl"].asJsonArray[0].asJsonObject
                    return durl["url"]?.asString
                }
            }
            logger.error("[方案B] API 解析失败: ${json["message"]?.asString ?: "无 durl 字段 (可能该画质仅支持DASH)"}")
            return null
        } catch (e: Exception) {
            logger.error("[方案B] 原生 API (Header伪装) 异常: ${e.message}")
            return null
        }
    }

    // 方案A的实现 (injahow)
    private fun downloadWithFallbackApi(bvId: String, quality: String): String? {
        val apiUrl = "https://api.injahow.cn/bparse/?bv=$bvId&q=$quality&format=mp4&otype=json"

        logger.info("[方案A] 正在请求 injahow API: $apiUrl")

        try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                logger.error("[方案A] API 解析失败: HTTP ${connection.responseCode}")
                return null
            }

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()

            val json = JsonParser.parseString(response).asJsonObject

            if (json.has("code") && json["code"].asInt == 0 && json.has("data")) {
                val data = json["data"].asJsonObject
                return data["url"]?.asString
            }
            logger.error("[方案A] API 解析失败: ${json["msg"]?.asString ?: "未知错误"}")
            return null
        } catch (e: Exception) {
            logger.error("[方案A] API 异常: ${e.message}")
            return null
        }
    }

    // 统一的视频文件下载函数
    @Throws(Exception::class)
    private fun downloadVideoFile(videoUrl: String, destination: File, referer: String = "https://www.bilibili.com/") {
        val videoConnection = URL(videoUrl).openConnection() as HttpURLConnection
        videoConnection.connectTimeout = 15000 // 15秒连接超时
        videoConnection.readTimeout = 180000  // 3分钟读取超时
        videoConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        videoConnection.setRequestProperty("Referer", referer)

        videoConnection.inputStream.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        videoConnection.disconnect()
    }


    private suspend fun waitForUserReply(group: Group, userId: Long, timeoutMillis: Long = 30000): String? {
        return try {
            val deferred = CompletableDeferred<String?>()
            val subscription = globalEventChannel().subscribeAlways<GroupMessageEvent> {
                if (it.group.id == group.id && it.sender.id == userId) {
                    deferred.complete(it.message.contentToString())
                }
            }

            withTimeout(timeoutMillis) {
                deferred.await()
            }.also {
                subscription.complete()
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
    }


    private suspend fun handleParsedBVId(group: Group, video: VideoReference, videoLink: String, senderId: Long) {
        if (isRateLimited(video.rateLimitKey, "Video")) {
            return // 处于冷却中，停止执行
        }
        val details = getVideoDetails(video.bvId) //
        val selectedPage = details?.selectedPage(video.page)

        if (details != null && video.page > 1 && details.pages.orEmpty().isNotEmpty() && selectedPage == null) {
            group.sendMessage("❌ 未找到该视频的 P${video.page}，当前共 ${details.pageCount()}P。")
            return
        }

        var message = ""

        if (details != null && Config.enableDetailedInfo) { //
            message += buildString {
                appendLine("【${details.selectedTitle(video.page)}】")
                if (details.pageCount() > 1) {
                    val currentPage = selectedPage?.page ?: video.page
                    appendLine("分P: P$currentPage/${details.pageCount()}")
                }
                appendLine("UP: ${details.owner.name}")
                appendLine("播放: ${details.stat.view}   弹幕: ${details.stat.danmaku}")
                appendLine("评论: ${details.stat.reply}   收藏: ${details.stat.favorite}")
                appendLine("投币: ${details.stat.coin}   分享: ${details.stat.share}")
                appendLine("点赞: ${details.stat.like}")
                appendLine("简介: ${details.desc}")
            }
        }

        if (Config.enableSendLink) {
            message += "\n链接: $videoLink"
        }

        if (message.isNotBlank()) {
            group.sendMessage(message)
        }

        if (details != null) {
            val durationMinutes = details.selectedDuration(video.page) / 60.0
            if (Config.minimumDuration > 0 && durationMinutes < Config.minimumDuration) {
                logger.info("视频 (BV:${video.bvId}, P:${video.page}) 太短 ($durationMinutes min)，已跳过下载。")
                if (Config.minDurationTip.isNotBlank()) { //
                    group.sendMessage(Config.minDurationTip) //
                }
                return
            }
            if (Config.maximumDuration > 0 && durationMinutes > Config.maximumDuration) {
                logger.info("视频 (BV:${video.bvId}, P:${video.page}) 太长 ($durationMinutes min)，已跳过下载。")
                if (Config.maxDurationTip.isNotBlank()) { //
                    group.sendMessage(Config.maxDurationTip) //
                }
                return
            }
        } else if (video.page > 1 && (Config.enableDownload || Config.askBeforeDownload)) {
            group.sendMessage("❌ 无法获取分P信息，已跳过下载，避免误下载 P1。")
            return
        }


        if (video.bvId != "未知BV号") {
            if (Config.askBeforeDownload) {
                group.sendMessage("📦 是否下载并发送该视频？请回复 ‘下载’ 或 ‘是’（30秒内有效）")
                try {
                    val reply = waitForUserReply(group, senderId)
                    val keywords = listOf("下载", "是", "要")
                    if (keywords.any { reply?.contains(it) == true }) {
                        proceedToDownload(group, video, details)
                    } else {
                        group.sendMessage("✅ 已忽略视频下载请求")
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.info("⌛ 下载请求超时，已跳过下载")
                }
            } else if (Config.enableDownload) {
                logger.info("🚀 自动下载模式，开始处理视频: ${video.bvId} P${video.page}")
                proceedToDownload(group, video, details)
            }
        }
    }


    override fun onEnable() {
        logger.info("BiliVideoParser 插件已启用")

        Config.reload()
        CommandManager.registerCommand(BiliVideoParserCommand)
        cleanupOldFiles()
        startAutoCleanupJob()

        globalEventChannel().subscribeAlways<GroupMessageEvent> {
            if (!Config.enableParsing) {
                return@subscribeAlways
            }

            if (!Config.isGroupAllowed(group.id)) {
                return@subscribeAlways
            }

            val rawText = message.content
            val miraiCode = message.serializeToMiraiCode()


            if (miraiCode.startsWith("[mirai:app")) {
                val content = message.content
                try {
                    val jsonStart = content.indexOf('{')
                    if (jsonStart != -1) {
                        val jsonStr = content.substring(jsonStart)
                        val json = JsonParser.parseString(jsonStr).asJsonObject

                        var bilibiliUrl: String? = null

                        if (json.has("meta") && json["meta"].asJsonObject.has("news")) {
                            val news = json["meta"].asJsonObject["news"].asJsonObject
                            bilibiliUrl = news["jumpUrl"]?.asString
                        }
                        else if (json.has("meta") && json["meta"].asJsonObject.has("detail_1")) {
                            val detail = json["meta"].asJsonObject["detail_1"].asJsonObject
                            bilibiliUrl = detail["qqdocurl"]?.asString
                        }

                        if (!bilibiliUrl.isNullOrBlank()) {
                            if (handleBilibiliUrl(bilibiliUrl, jsonStr)) {
                                return@subscribeAlways
                            }
                        } else {
                            handleMiniAppMessage()
                        }
                    }
                } catch (e: Exception) {
                    logger.warning("解析mirai:app消息异常: ${e.message}")
                    try {
                        handleMiniAppMessage()
                    } catch (e2: Exception) {
                        logger.warning("备用小程序解析方案也失败了: ${e2.message}")
                    }
                }
            } else {
                if (handleBilibiliUrl(rawText)) {
                    return@subscribeAlways
                }

                val b23Regex = Regex("""https?://(www\.)?b23\.tv/[A-Za-z0-9]+(?:[/?#][^\s"]*)?""")
                val biliLongRegex = Regex("""https?://(www\.)?bilibili\.com/video/(BV[0-9A-Za-z]+)(?:[/?#][^\s"]*)?""")

                val b23Match = b23Regex.find(rawText)
                val longMatch = biliLongRegex.find(rawText)

                if (b23Match != null) {
                    val shortUrl = b23Match.value
                    handleLinkMessage(shortUrl)
                } else if (longMatch != null) {
                    val longUrl = longMatch.value
                    val video = extractVideoReferenceFromUrl(longUrl)
                    if (video != null) {
                        val videoLink = if (Config.useShortLink) longUrl else video.longUrl()
                        handleParsedBVId(group, video, videoLink, sender.id)
                    }
                }
            }
        }
    }
}

data class MiniAppJsonData(val app: String, val meta: Meta) {
    data class Meta(val detail_1: Detail)
    data class Detail(val desc: String?, val qqdocurl: String, val appid: String)
}
