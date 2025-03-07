package com.bcz.bilivideoparser

import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value
import java.io.File

object Config : AutoSavePluginConfig("config") {
    var enableParsing: Boolean by value(true) // 是否启用解析功能
    var logMessages: Boolean by value(true) // 是否记录群消息日志
    var useShortLink: Boolean by value(true) // 是否使用短链接（b23.tv）

    // **获取 Mirai 存储 config.yml 的路径**
    private val configFile = File(BiliVideoParser.dataFolder, "config.yml")

    // **插件启动时强制写入完整的 config.yml**
    fun saveWithComments() {
        val configText = """
            # 是否启用 Bilibili 小程序解析
            enableParsing: $enableParsing
            
            # 是否记录收到的 QQ 群消息（日志输出）
            logMessages: $logMessages
            
            # 是否使用短链接（b23.tv），如果为 false，则使用长链接（bilibili.com）
            useShortLink: $useShortLink
        """.trimIndent()

        configFile.writeText(configText) // **强制写入 YAML**
    }
}