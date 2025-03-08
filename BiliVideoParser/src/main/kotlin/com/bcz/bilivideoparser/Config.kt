package com.bcz.bilivideoparser

import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.PluginDataHolder
import net.mamoe.mirai.console.data.PluginDataStorage
import net.mamoe.mirai.console.data.ValueDescription
import net.mamoe.mirai.console.data.value
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

object Config : AutoSavePluginConfig("BiliVideoParserConfig") {

    @ValueDescription("配置版本号，用于自动检测和更新旧版配置")
    var configVersion: Int by value(1)

    @ValueDescription("是否启用解析功能")
    var enableParsing: Boolean by value(true)

    @ValueDescription("是否记录群消息日志")
    var logMessages: Boolean by value(true)

    @ValueDescription("是否使用短链接（b23.tv）；若为 false 则使用长链接（bilibili.com）")
    var useShortLink: Boolean by value(true)

    // 修改此常量以触发自动更新（当旧版配置的 configVersion 小于此值时，会立即更新并保存配置）
    private const val CURRENT_CONFIG_VERSION = 2

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
    private fun forceSave() {
        try {
            val method = AutoSavePluginConfig::class.java.getDeclaredMethod("save")
            method.isAccessible = true
            method.invoke(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}