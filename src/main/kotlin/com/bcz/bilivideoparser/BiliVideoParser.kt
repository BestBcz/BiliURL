package com.bcz.bilivideoparser

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred



object BiliVideoParser : KotlinPlugin(
    JvmPluginDescription(
        id = "com.bcz.bilivideoparser",
        name = "BiliVideoParser",
        version = "1.1.6"
        //https://github.com/BestBcz/BiliURL
    ) {
        author("Bcz")
    }
)


{



    // 定义下载目录
    private val DOWNLOAD_DIR = Paths.get("bilidownload").toFile().apply {
    if (!exists()) mkdirs() // 创建目录如果不存在
    logger.info("下载目录已设置: $absolutePath")
}

    //删除旧文件
    private fun cleanupOldFiles() {
        val files = DOWNLOAD_DIR.listFiles()
        files?.forEach {
            if (it.exists() && it.isFile) {
                if (it.delete()) {
                    logger.info("清理旧文件: ${it.absolutePath}")
                } else {
                    logger.warning("无法删除文件: ${it.absolutePath}")
                }
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
            val process = ProcessBuilder(
                "yt-dlp", "-f", "bv*+ba*",
                "--merge-output-format", "mp4",
                "-o", outputFile.absolutePath,
                bilibiliUrl
            ).start()

            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroy()
                logger.warning("视频下载超时: $bvId")
                return null
            }

            return if (outputFile.exists()) outputFile else null
        } catch (e: IOException) {
            logger.error("视频下载失败: ${e.message}")
            return null
        }
    }

    private fun downloadThumbnail(url: String): File? {
        logger.info("尝试下载封面图: $url")
        val rawImageFile = File(DOWNLOAD_DIR, "raw_thumbnail_${url.hashCode()}.img")
        val jpgFile = File(DOWNLOAD_DIR, "thumbnail_${url.hashCode()}.jpg")

        try {
            // 下载原始图片
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            connection.inputStream.use { input ->
                rawImageFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 解码成 BufferedImage
            val image = ImageIO.read(rawImageFile)
            if (image == null) {
                logger.error("无法解码封面图（非标准图像格式）")
                rawImageFile.delete()
                return null
            }

            // 转换为 JPG 并保存
            ImageIO.write(image, "jpg", jpgFile)
            logger.info("封面图转 JPG 成功: ${jpgFile.absolutePath}")

            // 删除原始图片文件
            rawImageFile.delete()
            return jpgFile
        } catch (e: Exception) {
            logger.error("封面图处理失败: ${e.message}", e)
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
                logger.info("✅ 生成默认缩略图: ${defaultThumb.absolutePath}")
            } catch (e: Exception) {
                logger.error("❌ 生成默认缩略图失败: ${e.message}")
            }
        }
        return defaultThumb
    }
    // 检查视频文件是否稳定写入
    private fun waitUntilFileReady(file: File, timeoutMillis: Long = 15000): Boolean {
        val start = System.currentTimeMillis()
        var lastLength = -1L
        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (!file.exists()) {
                Thread.sleep(500)
                continue
            }
            val len = file.length()
            if (len > 0 && len == lastLength) return true
            lastLength = len
            Thread.sleep(500)
        }
        return false
    }

    /**
     * 发送视频消息
     */
    private suspend fun sendShortVideoMessage( group: Group, videoFile: File, thumbnailUrl: String? = null ) {
        if (!videoFile.exists()) {
            logger.warning("视频文件不存在: ${videoFile.absolutePath}")
            group.sendMessage("❌ 视频文件不存在")
            return
        }
        if (!waitUntilFileReady(videoFile)) {
            group.sendMessage("⚠️ 视频准备超时，请稍后再试")
            return
        }

        // 下载并发送封面图（如果提供了 thumbnailUrl）
        var thumbnailFile: File? = null
        if (thumbnailUrl != null) {
            thumbnailFile = downloadThumbnail(thumbnailUrl)
            if (thumbnailFile != null) {
                val thumbnailResource = thumbnailFile.toExternalResource("jpg")
                try {
                    val imageMessage = group.uploadImage(thumbnailResource)
                    group.sendMessage(imageMessage)
                    logger.info("✅ 封面图发送成功: ${thumbnailFile.absolutePath}")
                } catch (e: Exception) {
                    logger.error("⚠️ 封面图发送失败: ${e.message}", e)
                } finally {
                    withContext(Dispatchers.IO) {
                        thumbnailResource.close()
                    }
                }
            } else {
                logger.warning("无法下载封面图: $thumbnailUrl")
            }
        }

        // 使用下载的封面图作为缩略图，失败则使用默认缩略图
        val thumbnailToUse = thumbnailFile ?: generateDefaultThumbnail()
        val videoResource = videoFile.toExternalResource("mp4")
        val thumbnailResource = thumbnailToUse.toExternalResource("jpg")

        try {

            val shortVideo = group.uploadShortVideo(thumbnailResource, videoResource, videoFile.name)
            group.sendMessage(shortVideo)

            logger.info("✅ 视频短消息发送成功: ${videoFile.name}")
        } catch (e: Exception) {

            logger.error("⚠️ 视频发送失败: ${e.message}", e)
            //group.sendMessage("⚠️ 视频发送失败: ${e.message}")


        } finally {
            withContext(Dispatchers.IO) {
                videoResource.close()
            }
            withContext(Dispatchers.IO) {
                thumbnailResource.close()
            }
            // 删除相关文件
            videoFile.delete().let { logger.info("删除视频文件: ${videoFile.absolutePath}, 结果: $it") }
            thumbnailToUse.delete().let { logger.info("删除缩略图文件: ${thumbnailToUse.absolutePath}, 结果: $it") }
            thumbnailFile?.delete()?.let { logger.info("删除下载的封面图: ${thumbnailFile.absolutePath}, 结果: $it") }
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
            sendShortVideoMessage(group, videoFile, details?.pic)
        } else {
            logger.error("⚠️ 视频下载失败，请稍后重试")
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
                    group.sendMessage("⌛ 下载请求超时，已跳过下载")
                }
            } else if (Config.enableDownload) {
                proceedToDownload(group, bvId, details)
            }
        }
    }


    override fun onEnable() {
        logger.info("Bilibili 视频解析插件已启用 - 开始加载")

        Config.reload()

        logger.info("配置加载完成，enableParsing = ${Config.enableParsing}, enableDownload = ${Config.enableDownload}, enableDetailedInfo = ${Config.enableDetailedInfo}")

        CommandManager.registerCommand(BiliVideoParserCommand) // 注册控制台指令

        cleanupOldFiles()

        globalEventChannel().subscribeAlways<GroupMessageEvent> {   //收到群消息
            //logger.info("收到群消息，原始内容: ${this.message.serializeToMiraiCode()}")

            if (!Config.enableParsing) {
                logger.info("解析功能已禁用，跳过处理")
                return@subscribeAlways
            }

            val rawText = message.content
            val miraiCode = message.serializeToMiraiCode()

            if (miraiCode.startsWith("[mirai:app")) {
                // 处理小程序
                handleMiniAppMessage()
            } else {
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

        logger.info("Bilibili 视频解析插件已启用 - 加载完成")
    }
}



// 数据类映射小程序 JSON 结构
data class MiniAppJsonData(val app: String, val meta: Meta) {
    data class Meta(val detail_1: Detail)
    data class Detail(val desc: String?, val qqdocurl: String, val appid: String)
}
