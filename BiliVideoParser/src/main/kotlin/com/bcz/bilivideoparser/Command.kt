package com.bcz.bilivideoparser

import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.SimpleCommand
import net.mamoe.mirai.console.util.ConsoleExperimentalApi

@OptIn(ConsoleExperimentalApi::class)
object BiliVideoParserCommand : SimpleCommand(
    BiliVideoParser,
    primaryName = "bilivideoparser",
    secondaryNames = arrayOf("bvp"),
    description = "BiliVideoParser 配置命令"
) {
    @Handler
    suspend fun CommandSender.handle(option: String? = null, value: String? = null) {
        val isConsole = this is net.mamoe.mirai.console.command.ConsoleCommandSender
        val userQQ = when (this) {
            is net.mamoe.mirai.contact.User -> this.id // 直接是 User 的情况（例如好友或临时会话）
            is net.mamoe.mirai.console.command.MemberCommandSender -> this.user.id // 群成员发送的命令
            is net.mamoe.mirai.console.command.FriendCommandSender -> this.user.id // 好友发送的命令
            else -> null // 其他情况（如控制台）
        }

        val hasPermission = isConsole || (userQQ != null && Config.isAdmin(userQQ))

        if (!hasPermission) {
            sendMessage("⚠️ 你没有权限执行此命令")
            return
        }

        if (option == null) {
            sendMessage("用法: /bvp <option> <value>\n可用选项: " +
                    "enable#开关插件, " +
                    "shortlink#开关短连接, " +
                    "Info#开关详细信息, " +
                    "Download#开关下载视频, " +
                    "addadmin#添加管理员, " +
                    "removeadmin#移除管理员, " +
                    "listadmins#管理员列表")
            return
        }
        if (value == null) {
            sendMessage("❌ 请提供值，例如: /bvp enable true")
            return
        }

        when (option.lowercase()) {
            "enable" -> {
                val boolValue = value.toBooleanStrictOrNull() ?: value.toBoolean()
                if (boolValue == null) {
                    sendMessage("❌ 无效的布尔值：$value，应为 true/false 或 1/0")
                } else {
                    Config.enableParsing = boolValue
                    Config.forceSave()
                    sendMessage("✅ 配置已更新：enableParsing = $boolValue")
                }
            }
            "shortlink" -> {
                val boolValue = value.toBooleanStrictOrNull() ?: value.toBoolean()
                if (boolValue == null) {
                    sendMessage("❌ 无效的布尔值：$value，应为 true/false 或 1/0")
                } else {
                    Config.useShortLink = boolValue
                    Config.forceSave()
                    sendMessage("✅ 配置已更新：useShortLink = $boolValue")
                }
            }
            "Info" -> {
                val boolValue = value.toBooleanStrictOrNull() ?: value.toBoolean()
                if (boolValue == null) {
                    sendMessage("❌ 无效的布尔值：$value，应为 true/false 或 1/0")
                } else {
                    Config.enableDetailedInfo = boolValue
                    Config.forceSave()
                    sendMessage("✅ 配置已更新：useShortLink = $boolValue")
                }
            }
            "Download" -> {
                val boolValue = value.toBooleanStrictOrNull() ?: value.toBoolean()
                if (boolValue == null) {
                    sendMessage("❌ 无效的布尔值：$value，应为 true/false 或 1/0")
                } else {
                    Config.enableDownload = boolValue
                    Config.forceSave()
                    sendMessage("✅ 配置已更新：useShortLink = $boolValue")
                }
            }
            "addadmin" -> {
                val qqNumber = value.toLongOrNull()
                if (qqNumber == null || qqNumber <= 0) {
                    sendMessage("❌ 无效的 QQ 号：$value，应为正整数")
                } else if (Config.isAdmin(qqNumber)) {
                    sendMessage("❌ QQ 号 $qqNumber 已存在于管理员列表")
                } else {
                    Config.adminQQs.add(qqNumber)
                    Config.forceSave()
                    sendMessage("✅ 已添加管理员：QQ $qqNumber")

                }
            }
            "removeadmin" -> {
                if (value == null) {
                    sendMessage("❌ 请提供 QQ 号，例如: /bilivideoparser removeadmin 123456789")
                    return
                }
                val qqNumber = value.toLongOrNull()
                if (qqNumber == null || qqNumber <= 0) {
                    sendMessage("❌ 无效的 QQ 号：$value，应为正整数")
                } else if (!Config.isAdmin(qqNumber)) {
                    sendMessage("❌ QQ 号 $qqNumber 不在管理员列表中")
                } else if (Config.adminQQs.size <= 1) {
                    sendMessage("❌ 无法移除最后一个管理员")
                } else {
                    Config.adminQQs.remove(qqNumber)
                    Config.forceSave()
                    sendMessage("✅ 已移除管理员：QQ $qqNumber")
                }
            }
            "listadmins" -> {
                if (Config.adminQQs.isEmpty()) {
                    sendMessage("当前没有管理员")
                } else {
                    val admins = Config.adminQQs.joinToString(", ")
                    sendMessage("当前管理员列表：$admins")
                }
            }
            else -> sendMessage("❌ 未知配置项：$option，可用选项: enableparser, useshortlink")
        }
    }
}