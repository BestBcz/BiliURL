package com.bcz.bilivideoparser

import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.PluginDataHolder
import net.mamoe.mirai.console.data.PluginDataStorage
import net.mamoe.mirai.console.data.ValueDescription
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.util.ConsoleExperimentalApi


object Config : AutoSavePluginConfig("BiliVideoParserConfig") {

    @ValueDescription("配置版本号，用于自动检测和更新旧版配置，请勿自行修改")
    var configVersion: Int by value(1)

    @ValueDescription("是否启用解析功能")
    var enableParsing: Boolean by value(true)

    @ValueDescription("是否使用短链接（b23.tv）；若为 false 则使用长链接（bilibili.com）")
    var useShortLink: Boolean by value(true)

    @ValueDescription("是否显示详细视频信息（包括up主、播放量、评论数、简介、点赞、收藏、投币、转发）")
    var enableDetailedInfo: Boolean by value(true)

    @ValueDescription("是否启用视频下载功能")
    var enableDownload: Boolean by value(true)

    @ValueDescription("管理员 QQ 号列表，只有这些用户可以使用指令")
    var adminQQs: MutableList<Long> by value(mutableListOf(123456789L, 987654321L))

    fun isAdmin(qq: Long): Boolean {
        return qq in adminQQs
    }

    // 修改此常量以触发自动更新（当旧版配置的 configVersion 小于此值时，会立即更新并保存配置）
    private const val CURRENT_CONFIG_VERSION = 3

    @OptIn(ConsoleExperimentalApi::class)
    override fun onInit(owner: PluginDataHolder, storage: PluginDataStorage) {
        super.onInit(owner, storage)
        if (configVersion < CURRENT_CONFIG_VERSION) {
            // 这里可以加入旧版配置的迁移逻辑
            configVersion = CURRENT_CONFIG_VERSION
            // 强制保存配置文件（默认 AutoSavePluginConfig 只在退出时保存）
            forceSave()
        }
    }

    // 利用反射调用父类中私有的 save() 方法，达到立即保存的效果
    fun forceSave() {
        try {
            val method = AutoSavePluginConfig::class.java.getDeclaredMethod("save")
            method.isAccessible = true
            method.invoke(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}