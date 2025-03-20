package com.bcz.bilivideoparser

import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.SimpleCommand

@OptIn(ConsoleExperimentalApi::class)
object BiliVideoParserCommand : SimpleCommand(
    BiliVideoParser,
    primaryName = "bilivideoparser",
    secondaryNames = arrayOf("bvp"),
    description = "BiliVideoParser 配置命令"
) {
    @Handler
    suspend fun CommandSender.handle(option: String? = null, value: String? = null) {
        val isConsole = this is ConsoleCommandSender
        val userQQ = when (this) {
            is User -> this.id
            is MemberCommandSender -> this.user.id
            is FriendCommandSender -> this.user.id
            else -> null
        }

        // 检查 LuckPerms 权限或 Config.adminQQs
        val commandPermission: Permission = this@BiliVideoParserCommand.permission
        val hasLuckPermsPermission = this.hasPermission(commandPermission) // 直接使用扩展函数
        val hasPermission = isConsole || (userQQ != null && (Config.isAdmin(userQQ) || hasLuckPermsPermission))

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
                // 不需要 value，直接处理
                if (Config.adminQQs.isEmpty()) {
                    sendMessage("当前没有管理员")
                } else {
                    val admins = Config.adminQQs.joinToString(", ")
                    sendMessage("当前管理员列表：$admins")
                }
            }
            else -> sendMessage("❌ 未知配置项：$option，可用选项: enableparser, useshortlink, addadmin, removeadmin, listadmins")
        }
    }
}