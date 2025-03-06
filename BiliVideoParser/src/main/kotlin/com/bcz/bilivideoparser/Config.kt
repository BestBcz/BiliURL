package com.bcz.bilivideoparser


import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value

object Config : AutoSavePluginConfig("config") {
    var enableParsing: Boolean by value(true) // 是否启用解析功能
    var logMessages: Boolean by value(true) // 是否记录群消息日志
}