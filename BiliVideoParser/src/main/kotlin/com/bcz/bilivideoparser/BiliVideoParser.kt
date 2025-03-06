package com.bcz.bilivideoparser

import com.google.gson.Gson
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.data.content
import net.mamoe.mirai.message.data.messageChainOf

object BiliVideoParser : KotlinPlugin(
    JvmPluginDescription(
        id = "com.bcz.bilivideoparser",
        name = "BiliVideoParser",
        version = "1.0.0"
//      https://github.com/BestBcz
    )
) {
    override fun onEnable() {
        logger.info("Bilibili 视频解析插件已启用 - 开始加载")

        globalEventChannel().subscribeAlways<GroupMessageEvent> {
            logger.info("收到群消息: ${this.message.content}") // 记录每条消息

            if (this.message.serializeToMiraiCode().startsWith("""[mirai:app""")) {
                val gotRawData = this.message.content
                try {
                    val jsonData = Gson().fromJson(gotRawData, MiniAppJsonData::class.java)
                    if (jsonData.app == "com.tencent.miniapp_01" && jsonData.meta.detail_1.appid == "1109937557") {
                        val videoTitle = jsonData.meta.detail_1.title // 获取视频标题
                        val shortLink = jsonData.meta.detail_1.qqdocurl.substringBefore('?')
                            .replace("https://www.bilibili.com/video/", "https://b23.tv/")

                        this.group.sendMessage("【${videoTitle}-哔哩哔哩】 $shortLink")
                        logger.info("Bilibili 小程序解析成功并发送")
                    }
                } catch (e: Exception) {
                    logger.error("解析 Bilibili 小程序出错: ${e.message}")
                }
            }
        }

        logger.info("Bilibili 视频解析插件已启用 - 加载完成")
    }
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
)