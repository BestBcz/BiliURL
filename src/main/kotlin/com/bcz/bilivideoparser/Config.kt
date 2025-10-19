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

    @ValueDescription("白名单群号列表（优先生效）")
    var groupWhiteList: MutableSet<Long> by value(mutableSetOf())

    @ValueDescription("黑名单群号列表")
    var groupBlackList: MutableSet<Long> by value(mutableSetOf())

    @ValueDescription("是否发送解析后的视频链接")
    var enableSendLink: Boolean by value(true)

    @ValueDescription("是否在下载前询问用户-默认false")
    var askBeforeDownload: Boolean by value(false)

    @ValueDescription("用于访问新版B站动态的登录Cookie，SESSDATA开头")
    var bilibiliCookie: String by value("")

    @ValueDescription("是否下载并发送视频封面图")
    var enableThumbnail: Boolean by value(true)

    /**
     * 群组权限判断逻辑
     * 1. 白名单优先：若白名单有群号，则只在白名单中的群聊开启
     * 2. 若白名单为空，则不使用白名单，除黑名单的群都开启下载
     * 3. 若都不填，那默认全部开启
     */
    fun isGroupAllowed(groupId: Long): Boolean {
        return when {
            groupWhiteList.isNotEmpty() -> groupId in groupWhiteList
            groupBlackList.isNotEmpty() -> groupId !in groupBlackList
            else -> true
        }
    }

    fun isAdmin(qq: Long): Boolean {
        return qq in adminQQs
    }

    private const val CURRENT_CONFIG_VERSION = 4

    @OptIn(ConsoleExperimentalApi::class)
    override fun onInit(owner: PluginDataHolder, storage: PluginDataStorage) {
        super.onInit(owner, storage)
        if (configVersion < CURRENT_CONFIG_VERSION) {
            configVersion = CURRENT_CONFIG_VERSION
        }
    }
}