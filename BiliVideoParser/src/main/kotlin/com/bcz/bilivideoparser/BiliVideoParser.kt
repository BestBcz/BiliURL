package com.bcz.bilivideoparser

import com.google.gson.Gson
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.data.content
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object BiliVideoParser : KotlinPlugin(
    JvmPluginDescription(
        id = "com.bcz.bilivideoparser",
        name = "BiliVideoParser",
        version = "1.0.2"
        //https://github.com/BestBcz/BiliURL
    )
) {
    //BV号重定向解析真实链接
    fun getRealBilibiliUrl(shortUrl: String): String {
        return try {
            val connection = URL(shortUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connect()

            val realUrl = connection.getHeaderField("Location") ?: return "未知BV号"

            // **正则提取 BV 号**
            val bvIdRegex = Regex("""BV[0-9A-Za-z]+""")
            val bvIdMatch = bvIdRegex.find(realUrl)

            bvIdMatch?.value ?: "未知BV号"
        } catch (e: Exception) {
            "未知BV号"
        }
    }

    override fun onEnable() {
        logger.info("Bilibili 视频解析插件已启用 - 开始加载")

        Config.reload() // **加载配置**

        globalEventChannel().subscribeAlways<GroupMessageEvent> {
            if (Config.logMessages) {
                logger.info("收到群消息: ${this.message.content}") // **记录消息**
            }

            if (!Config.enableParsing) return@subscribeAlways // **如果解析被禁用，则直接返回**

            if (this.message.serializeToMiraiCode().startsWith("""[mirai:app""")) {
                val gotRawData = this.message.content
                try {
                    val jsonData = Gson().fromJson(gotRawData, MiniAppJsonData::class.java)
                    if (jsonData.app == "com.tencent.miniapp_01" && jsonData.meta.detail_1.appid == "1109937557") {
                        val videoTitle = jsonData.meta.detail_1.title // **获取视频标题**
                        val shortUrl = jsonData.meta.detail_1.qqdocurl // **b23.tv 短链**

                        // **解析 BV 号**
                        val bvId = getRealBilibiliUrl(shortUrl)

                        // **根据配置选择使用长链接或短链接**
                        val videoLink = if (Config.useShortLink) {
                            shortUrl
                        } else {
                            "https://www.bilibili.com/video/$bvId/"
                        }

                        this.group.sendMessage("【${videoTitle}-哔哩哔哩】 $videoLink")
                        logger.info("Bilibili 小程序解析成功并发送，使用短链接: ${Config.useShortLink}, 解析到 BV 号: $bvId")
                    }
                } catch (e: Exception) {
                    logger.error("解析 Bilibili 小程序出错: ${e.message}")
                }
            }
        }

        logger.info("Bilibili 视频解析插件已启用 - 加载完成")
    }



    // 定义数据类，映射 JSON 结构
data class MiniAppJsonData(
    val app: String,
    val meta: MetaData
)

data class MetaData(
    val detail_1: DetailData
)

data class DetailData(
    val appid: String,
    val title: String,
    val qqdocurl: String
)}