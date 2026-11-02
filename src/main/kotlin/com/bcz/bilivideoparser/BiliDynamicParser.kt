package com.bcz.bilivideoparser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
        return BiliDynamicClient(
            request = {
                BiliVideoParser.biliHttp.prepareBrowserSession()
                BiliVideoParser.biliHttp.get(it).body
            },
            warning = { BiliVideoParser.logger.warning(it) },
            info = { BiliVideoParser.logger.info(it) }
        ).getDynamic(dynamicId, qqAppMessage)
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
                        try {
                            imageFile.toExternalResource().use { imageResource ->
                                val imageMessage = group.uploadImage(imageResource)
                                group.sendMessage(imageMessage)
                            }
                        } finally {
                            imageFile.delete()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BiliVideoParser.logger.warning("动态图片发送失败: ${e.message}")
                }
            }
        }
    }

    //下载缩略图
    private suspend fun downloadThumbnail(url: String): File? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var tempFile: File? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("User-Agent", BiliHttpClient.USER_AGENT)
                connection.setRequestProperty("Referer", "https://www.bilibili.com/")

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // 统一使用 DOWNLOAD_DIR
                    val imageFile = File.createTempFile("dynamic_", ".jpg", BiliVideoParser.DOWNLOAD_DIR)
                    tempFile = imageFile
                    connection.inputStream.use { input ->
                        imageFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    imageFile
                } else {
                    BiliVideoParser.logger.warning("动态图片下载失败: HTTP $responseCode")
                    null
                }
            } catch (e: CancellationException) {
                tempFile?.delete()
                throw e
            } catch (e: Exception) {
                tempFile?.delete()
                BiliVideoParser.logger.warning("动态图片下载失败: ${e.message}")
                null
            } finally {
                connection?.disconnect()
            }
        }
    }
}
