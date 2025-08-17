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
import okio.IOException
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import com.bcz.bilivideoparser.BiliDynamicParser
import com.google.gson.JsonParser


object BiliVideoParser : KotlinPlugin(
    JvmPluginDescription(
        id = "com.bcz.bilivideoparser",
        name = "BiliVideoParser",
        version = "1.2.1"
        //https://github.com/BestBcz/BiliURL
    ) {
        author("Bcz")
    }
)


{



    // 定义下载目录
    private val DOWNLOAD_DIR = Paths.get("bilidownload").toFile().apply {
    if (!exists()) mkdirs() // 创建目录如果不存在
}

    //删除旧文件
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


                   //定时清理
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



    // BV号重定向解析真实链接
    private fun getRealBilibiliUrl(shortUrl: String): String {
        return try {
            val connection = URL(shortUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connect()
            val realUrl = connection.getHeaderField("Location") ?: return "未知BV号"
            val bvIdRegex = Regex("""BV[0-9A-Za-z]+""")
            val bvIdMatch = bvIdRegex.find(realUrl)
            bvIdMatch?.value ?: "未知BV号"
        } catch (e: Exception) {
            "未知BV号"
        }
    }

    // 从URL中提取视频ID（支持短链和长链）
    private fun extractVideoIdFromUrl(url: String): String? {
        return try {
            // 1. 先检查是否已经是长链接格式
            val bvIdRegex = Regex("""BV[0-9A-Za-z]+""")
            val directMatch = bvIdRegex.find(url)
            if (directMatch != null) {
                return directMatch.value
            }
            
            // 2. 处理短链接跳转
            if (url.contains("b23.tv/")) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connect()
                val realUrl = connection.getHeaderField("Location") ?: return null
                val bvIdMatch = bvIdRegex.find(realUrl)
                return bvIdMatch?.value
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
        val pic: String // 封面图 URL
    ) {
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


    private fun downloadBiliVideo(bvId: String): File? {
        val outputFile = File(DOWNLOAD_DIR, "downloaded_video_$bvId.mp4")
        try {
            val bilibiliUrl = "https://www.bilibili.com/video/$bvId"
            logger.info("开始下载视频: $bvId")
            
            // 下载视频，限制文件大小小于100MB
            val process = ProcessBuilder(
                "yt-dlp", 
                "-f", "bv+ba", 
                "-S", "+size",
                "--merge-output-format", "mp4",
                "--max-filesize", "100M",
                "-o", outputFile.absolutePath,
                bilibiliUrl
            ).redirectErrorStream(true).start()

            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            reader.close()

            val exitCode = if (process.waitFor(180, TimeUnit.SECONDS)) {
                process.exitValue()
            } else {
                process.destroy()
                logger.warning("视频下载超时: $bvId")
                -1
            }

            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                return outputFile
            } else {
                logger.error("视频下载失败: $bvId")
                return null
            }
        } catch (e: IOException) {
            logger.error("视频下载异常: ${e.message}")
            return null
        }
    }

    private fun downloadThumbnail(url: String): File? {
        val rawImageFile = File(DOWNLOAD_DIR, "raw_thumbnail_${url.hashCode()}.img")
        val jpgFile = File(DOWNLOAD_DIR, "thumbnail_${url.hashCode()}.jpg")

        try {
            // 下载原始图片
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

            // 解码成 BufferedImage
            val originalImage = ImageIO.read(rawImageFile)
            if (originalImage == null) {
                logger.error("无法解码封面图")
                rawImageFile.delete()
                return null
            }

            // 创建新的RGB图像来处理透明背景
            val rgbImage = BufferedImage(originalImage.width, originalImage.height, BufferedImage.TYPE_INT_RGB)
            val graphics = rgbImage.createGraphics()
            
            // 设置白色背景（JPG不支持透明）
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, originalImage.width, originalImage.height)
            
            // 绘制原图像
            graphics.drawImage(originalImage, 0, 0, null)
            graphics.dispose()

            // 转换为 JPG 并保存
            val success = ImageIO.write(rgbImage, "jpg", jpgFile)
            if (!success) {
                logger.error("封面图转JPG失败")
                rawImageFile.delete()
                jpgFile.delete()
                return null
            }
            
            // 删除原始图片文件
            rawImageFile.delete()
            return jpgFile
        } catch (e: Exception) {
            logger.error("封面图处理失败: ${e.message}")
            rawImageFile.delete()
            jpgFile.delete()
            return null
        }
    }


    /**
     * 生成默认缩略图（黑色 1656×931）
     */
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


     //发送视频消息 -并行处理

    private suspend fun sendShortVideoMessage(group: Group, videoFile: File, thumbnailUrl: String? = null) {
        if (!videoFile.exists()) {
            logger.warning("视频文件不存在: ${videoFile.name}")
            return
        }

        var thumbnailFile: File? = null
        var videoResource: net.mamoe.mirai.utils.ExternalResource? = null
        var thumbnailResource: net.mamoe.mirai.utils.ExternalResource? = null

        try {
            // 并行处理缩略图下载和视频资源准备
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

            // 等待缩略图下载完成
            thumbnailFile = thumbnailJob.await()
            
            // 准备缩略图资源
            val thumbnailToUse = thumbnailFile ?: generateDefaultThumbnail()
            thumbnailResource = thumbnailToUse.toExternalResource("jpg")
            
            // 等待视频资源准备完成
            videoResource = videoResourceJob.await()
            
            if (videoResource == null) {
                logger.error("视频资源准备失败")
                return
            }

            // 发送封面图（可选，失败不影响视频发送）
            if (thumbnailFile != null) {
                try {
                    withTimeout(5000) { // 5秒超时
                        val imageMessage = group.uploadImage(thumbnailResource)
                        group.sendMessage(imageMessage)
                    }
                } catch (e: Exception) {
                    logger.warning("封面图发送失败，继续发送视频: ${e.message}")
                }
            }

            // 发送视频 - 使用更长的超时时间
            try {
                withTimeout(30000) { // 30秒超时
                    val shortVideo = group.uploadShortVideo(thumbnailResource, videoResource, videoFile.name)
                    group.sendMessage(shortVideo)
                    logger.info("✅ 视频发送成功: ${videoFile.name}")
                }
            } catch (e: Exception) {
                // 检查是否是超时错误
                if (e.message?.contains("timeout", ignoreCase = true) == true || 
                    e.message?.contains("Timed out", ignoreCase = true) == true) {
                    logger.warning("视频发送超时，但可能已成功发送: ${e.message}")
                    // 不向用户显示错误，因为可能实际发送成功了
                } else {
                    logger.error("视频发送失败: ${e.message}")
                }
            }

        } catch (e: Exception) {
            logger.error("视频发送过程异常: ${e.message}")
        } finally {
            // 清理资源
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
            
            // 延迟删除文件，确保发送完成
            withContext(Dispatchers.IO) {
                delay(2000) // 等待2秒确保发送完成
                try {
                    videoFile.delete()
                    thumbnailFile?.delete()
                } catch (e: Exception) {
                    logger.warning("删除临时文件失败: ${e.message}")
                }
            }
        }
    }

     //小程序消息处理
    private suspend fun GroupMessageEvent.handleMiniAppMessage() {
        val jsonData = Gson().fromJson(message.content, MiniAppJsonData::class.java)
        if (jsonData.app == "com.tencent.miniapp_01" &&
            jsonData.meta.detail_1.appid == "1109937557") {

            val shortUrl = jsonData.meta.detail_1.qqdocurl
            jsonData.meta.detail_1.desc ?: "哔哩哔哩"
            val bvId = getRealBilibiliUrl(shortUrl)
            val videoLink = if (Config.useShortLink) shortUrl else "https://www.bilibili.com/video/$bvId"

            handleParsedBVId(group, bvId, videoLink, sender.id)
        }
    }

    //分享链接处理
    private suspend fun GroupMessageEvent.handleLinkMessage(shortUrl: String) {
        val bvId = getRealBilibiliUrl(shortUrl)
        val videoLink = if (Config.useShortLink) shortUrl else "https://www.bilibili.com/video/$bvId"


        handleParsedBVId(group, bvId, videoLink, sender.id)
    }

    private suspend fun proceedToDownload(group: Group, bvId: String, details: VideoDetails?) {
    
        val videoFile = downloadBiliVideo(bvId)
        if (videoFile != null) {
            val fileSizeMB = videoFile.length() / (1024 * 1024)
            logger.info("✅ 视频下载完成 (${fileSizeMB}MB)，正在发送...")

            sendShortVideoMessage(group, videoFile, details?.pic)
        } else {
            logger.error("❌ 视频下载失败，可能原因：\n1. 视频文件过大\n2. 网络连接问题\n3. yt-dlp 工具未正确安装")
        }
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
                subscription.complete() // 关闭监听器
            }
        } catch (e: TimeoutCancellationException) {
            null
        }
    }


    //公共处理函数
    private suspend fun handleParsedBVId(group: Group, bvId: String, videoLink: String, senderId: Long) {
        val details = getVideoDetails(bvId)

        var message = ""

        if (details != null && Config.enableDetailedInfo) {
            message += buildString {
                appendLine("【${details.title}】")
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

        if (bvId != "未知BV号") {
            if (Config.askBeforeDownload) {
                group.sendMessage("📦 是否下载并发送该视频？请回复 ‘下载’ 或 ‘是’（30秒内有效）")
                try {
                    val reply = waitForUserReply(group, senderId)
                    val keywords = listOf("下载", "是", "要")
                    if (keywords.any { reply?.contains(it) == true }) {
                        proceedToDownload(group, bvId, details)
                    } else {
                        group.sendMessage("✅ 已忽略视频下载请求")
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.info("⌛ 下载请求超时，已跳过下载")
                }
            } else if (Config.enableDownload) {
                logger.info("🚀 自动下载模式，开始处理视频: $bvId")
                proceedToDownload(group, bvId, details)
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

            // 检查群组黑白名单权限
            if (!Config.isGroupAllowed(group.id)) {
                return@subscribeAlways
            }

            val rawText = message.content
            val miraiCode = message.serializeToMiraiCode()

            // 优先处理mirai:app消息中的动态分享
            if (miraiCode.startsWith("[mirai:app")) {
                val content = message.content
                try {
                    val jsonStart = content.indexOf('{')
                    if (jsonStart != -1) {
                        val jsonStr = content.substring(jsonStart)
                        val json = JsonParser.parseString(jsonStr).asJsonObject
                        // 处理QQ分享卡片
                        if (json.has("meta") && json["meta"].asJsonObject.has("news")) {
                            val news = json["meta"].asJsonObject["news"].asJsonObject
                            val jumpUrl = news["jumpUrl"]?.asString ?: ""
                            val dynamicId = BiliDynamicParser.extractDynamicIdFromAnyUrl(jumpUrl)
                            if (dynamicId != null) {
                                val result = BiliDynamicParser.parseDynamic(jumpUrl, jsonStr)
                                if (result != null) {
                                    BiliDynamicParser.sendDynamicMessage(group, result)
                                    return@subscribeAlways
                                } else {
                                    // 动态解析失败，尝试解析其中的视频链接
                                    logger.info("动态解析失败，尝试解析视频链接: $jumpUrl")
                                }
                            }
                            
                            // 如果动态解析失败或不是动态链接，尝试解析视频链接
                            val bvIdFromJumpUrl = extractVideoIdFromUrl(jumpUrl)
                            if (bvIdFromJumpUrl != null) {
                                logger.info("从QQ分享卡片中检测到视频链接: $jumpUrl -> $bvIdFromJumpUrl")
                                val videoLink = if (Config.useShortLink) jumpUrl else "https://www.bilibili.com/video/$bvIdFromJumpUrl"
                                handleParsedBVId(group, bvIdFromJumpUrl, videoLink, sender.id)
                                return@subscribeAlways
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warning("解析mirai:app消息异常: ${e.message}")
                }
                // 其他小程序处理...
                handleMiniAppMessage()
            } else {
                // 检查文本消息中的动态链接（支持t.bilibili.com、opus、b23.tv等）
                val dynamicId = BiliDynamicParser.extractDynamicIdFromAnyUrl(rawText)
                if (dynamicId != null) {
                    val result = BiliDynamicParser.parseDynamic(rawText)
                    if (result != null) {
                        BiliDynamicParser.sendDynamicMessage(group, result)
                    } else {
                        group.sendMessage("❌ 解析动态失败或动态不存在")
                    }
                    return@subscribeAlways
                }
                // 检查短链接和长链接
                val b23Regex = Regex("""https?://(www\.)?b23\.tv/[A-Za-z0-9]+""")
                val biliLongRegex = Regex("""https?://(www\.)?bilibili\.com/video/(BV[0-9A-Za-z]+)""")

                val b23Match = b23Regex.find(rawText)
                val longMatch = biliLongRegex.find(rawText)

                if (b23Match != null) {
                    val shortUrl = b23Match.value
                    handleLinkMessage(shortUrl)
                } else if (longMatch != null) {
                    val bvId = longMatch.groupValues[2]
                    val longUrl = longMatch.value
                    handleParsedBVId(group, bvId, longUrl, sender.id)
                }
            }
        }
    }
}



// 数据类映射小程序 JSON 结构
data class MiniAppJsonData(val app: String, val meta: Meta) {
    data class Meta(val detail_1: Detail)
    data class Detail(val desc: String?, val qqdocurl: String, val appid: String)
}
