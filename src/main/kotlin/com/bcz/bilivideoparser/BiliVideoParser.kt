package com.bcz.bilivideoparser

import com.google.gson.Gson
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.utils.MiraiInternalApi
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.OptIn
import okio.IOException
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import javax.imageio.ImageIO
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Paths
import net.mamoe.mirai.console.command.CommandManager




object BiliVideoParser : KotlinPlugin(
    JvmPluginDescription(
        id = "com.bcz.bilivideoparser",
        name = "BiliVideoParser",
        version = "1.1.3"
        //https://github.com/BestBcz/BiliURL
    ) {
        author("Bcz")
        // author 和 info 可以删除.
    }
)


{



    // 定义下载目录
    private val DOWNLOAD_DIR = Paths.get("bilidownload").toFile().apply {
    if (!exists()) mkdirs() // 创建目录如果不存在
    logger.info("下载目录已设置: ${absolutePath}")
}



    // BV号重定向解析真实链接
    fun getRealBilibiliUrl(shortUrl: String): String {
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

    fun getVideoDetails(bvId: String): VideoDetails? {
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

    fun downloadBiliVideo(bvId: String): File? {
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

    fun downloadThumbnail(url: String): File? {
        logger.info("尝试下载封面图: $url")
        val outputFile = File(DOWNLOAD_DIR, "thumbnail_${url.hashCode()}.jpg")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            val input = connection.inputStream
            val output = FileOutputStream(outputFile)
            input.copyTo(output)
            output.close()
            input.close()
            if (outputFile.exists() && outputFile.length() > 0) {
                logger.info("封面图下载成功: ${outputFile.absolutePath}")
                return outputFile
            } else {
                logger.warning("封面图下载失败（文件未生成或为空）")
                outputFile.delete()
                return null
            }
        } catch (e: Exception) {
            logger.error("封面图下载失败: ${e.message}", e)
            return null
        }
    }


    /**
     * 生成默认缩略图（黑色 1656×931）
     */
    fun generateDefaultThumbnail(): File {
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

    /**
     * 发送视频消息
     */
    @OptIn(MiraiInternalApi::class)
    suspend fun sendShortVideoMessage(group: Group, videoFile: File, thumbnailUrl: String? = null) {
        if (!videoFile.exists()) {
            logger.warning("视频文件不存在: ${videoFile.absolutePath}")
            group.sendMessage("❌ 视频文件不存在")
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
                    thumbnailResource.close()
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
            group.sendMessage("⚠️ 视频发送失败: ${e.message}")
        } finally {
            videoResource.close()
            thumbnailResource.close()
            // 删除相关文件
            videoFile.delete().let { logger.info("删除视频文件: ${videoFile.absolutePath}, 结果: $it") }
            thumbnailToUse.delete().let { logger.info("删除缩略图文件: ${thumbnailToUse.absolutePath}, 结果: $it") }
            thumbnailFile?.delete()?.let { logger.info("删除下载的封面图: ${thumbnailFile.absolutePath}, 结果: $it") }
        }
    }

    override fun onEnable() {
        logger.info("Bilibili 视频解析插件已启用 - 开始加载")
        Config.reload()
        logger.info("配置加载完成，enableParsing = ${Config.enableParsing}, enableDownload = ${Config.enableDownload}, enableDetailedInfo = ${Config.enableDetailedInfo}")
        CommandManager.registerCommand(BiliVideoParserCommand) // 注册控制台指令

        globalEventChannel().subscribeAlways<GroupMessageEvent> {   //收到群消息
            //logger.info("收到群消息，原始内容: ${this.message.serializeToMiraiCode()}")

            if (!Config.enableParsing) {
                logger.info("解析功能已禁用，跳过处理")
                return@subscribeAlways
            }

            val miraiCode = this.message.serializeToMiraiCode()
            //logger.info("检查消息是否为小程序: $miraiCode")
            if (miraiCode.startsWith("[mirai:app")) {
                val gotRawData = this.message.content
                logger.info("检测到小程序消息，尝试解析: $gotRawData")
                try {
                    val jsonData = Gson().fromJson(gotRawData, MiniAppJsonData::class.java)
                    if (jsonData.app == "com.tencent.miniapp_01" && jsonData.meta.detail_1.appid == "1109937557") {
                        val videoTitle = jsonData.meta.detail_1.desc ?: "哔哩哔哩"
                        val shortUrl = jsonData.meta.detail_1.qqdocurl

                        val bvId = getRealBilibiliUrl(shortUrl)
                        val videoLink = if (Config.useShortLink) {
                            shortUrl
                        } else {
                            "https://www.bilibili.com/video/$bvId/"
                        }
                        val sanitizedTitle = videoTitle
                            .replace("[", "【")
                            .replace("]", "】")
                            .trim()

                        var messageToSend = "【$sanitizedTitle - 哔哩哔哩】"
                        logger.info("Bilibili 小程序解析成功并准备发送，使用短链接: ${Config.useShortLink}, 解析到 BV 号: $bvId")
                        logger.debug("原始desc字段值: ${jsonData.meta.detail_1.desc}")
                        logger.debug("处理后标题: $sanitizedTitle")

                        var thumbnailUrl: String? = null
                        if (Config.enableDetailedInfo && bvId != "未知BV号") {
                            val details = getVideoDetails(bvId)
                            if (details != null) {
                                val detailMsg = buildString {
                                    appendLine("【${details.title}】")
                                    appendLine("UP: ${details.owner.name}")
                                    appendLine("播放: ${details.stat.view}   弹幕: ${details.stat.danmaku}")
                                    appendLine("评论: ${details.stat.reply}   收藏: ${details.stat.favorite}")
                                    appendLine("投币: ${details.stat.coin}   分享: ${details.stat.share}")
                                    appendLine("点赞: ${details.stat.like}")
                                    appendLine("简介: ${details.desc}")
                                    appendLine("链接: $videoLink")
                                }
                                messageToSend += "\n" + detailMsg
                                thumbnailUrl = details.pic
                            } else {
                                logger.warning("无法获取视频详细信息，BV号: $bvId")
                                messageToSend += "\n链接: $videoLink"
                            }
                        } else {
                            messageToSend += "\n链接: $videoLink"
                        }

                        this.group.sendMessage(messageToSend)
                        logger.info("消息发送完成")

                        //logger.info("检查下载功能是否启用: enableDownload = ${Config.enableDownload}")
                        if (Config.enableDownload) {
                            logger.info("开始下载视频: $bvId")
                            val videoFile = downloadBiliVideo(bvId)
                            if (videoFile != null) {
                                logger.info("视频文件下载成功: ${videoFile.absolutePath}")
                                sendShortVideoMessage(this@subscribeAlways.group, videoFile, thumbnailUrl)
                            } else {
                                logger.warning("视频下载失败，返回 null")
                                group.sendMessage("⚠️ 视频下载失败，请检查日志")
                            }
                        } else {
                            logger.info("下载功能未启用，跳过下载")
                        }
                    } else {
                        //logger.info("小程序不是 Bilibili 的，跳过处理")
                    }
                } catch (e: Exception) {
                    logger.error("解析 Bilibili 小程序出错: ${e.message}", e)
                }
            } else {
                //logger.info("消息不是小程序，跳过处理")
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

data class MetaData(val detail_1: DetailData)

data class DetailData(
    val appid: String,
    val title: String,
    val qqdocurl: String,
    val desc: String
)