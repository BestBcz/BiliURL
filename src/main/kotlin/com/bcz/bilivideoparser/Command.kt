package com.bcz.bilivideoparser

import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.console.command.CommandSender

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

        val commandPermission: Permission = this@BiliVideoParserCommand.permission
        val hasLuckPermsPermission = this.hasPermission(commandPermission)
        val hasPermission = isConsole || (userQQ != null && (Config.isAdmin(userQQ) || hasLuckPermsPermission))

        if (!hasPermission) {
            sendMessage("⚠️ 你没有权限执行此命令")
            return
        }

        if (option == null) {
            sendMessage(
                """
                用法: /bvp <option> [value]
                可用选项:
                enable # 开关插件
                shortlink # 开关短链接
                info # 开关详细信息
                download # 开关下载视频
                addadmin # 添加管理员
                removeadmin # 移除管理员
                listadmins # 管理员列表
                addwhite # 添加群白名单
                addblack # 添加群黑名单
                removewhite # 移除白名单
                removeblack # 移除黑名单
                listgroups # 查看群组列表
                sendlink # 是否发送解析后的视频链接
                askdownload 是否开启询问
                """.trimIndent()
            )
            return
        }

        when (option.lowercase()) {
            "enable" -> updateBoolConfig("enableParsing", value) { Config.enableParsing = it }
            "shortlink" -> updateBoolConfig("useShortLink", value) { Config.useShortLink = it }
            "info" -> updateBoolConfig("enableDetailedInfo", value) { Config.enableDetailedInfo = it }
            "download" -> updateBoolConfig("enableDownload", value) { Config.enableDownload = it }
            "sendlink" -> updateBoolConfig("enableSendLink", value) { Config.enableSendLink = it }
            "askdownload" -> updateBoolConfig("askBeforeDownload", value) { Config.askBeforeDownload = it }

            "addadmin" -> modifyAdmin(value, add = true)
            "removeadmin" -> modifyAdmin(value, add = false)
            "listadmins" -> {
                if (Config.adminQQs.isEmpty()) {
                    sendMessage("当前没有管理员")
                } else {
                    val admins = Config.adminQQs.joinToString(", ")
                    sendMessage("当前管理员列表：$admins")
                }
            }

            // ✅ 群组控制相关
            "addwhite" -> modifyGroupList(value, whitelist = true, add = true)
            "removewhite" -> modifyGroupList(value, whitelist = true, add = false)
            "addblack" -> modifyGroupList(value, whitelist = false, add = true)
            "removeblack" -> modifyGroupList(value, whitelist = false, add = false)
            "listgroups" -> {
                val white = Config.groupWhiteList.joinToString(", ")
                val black = Config.groupBlackList.joinToString(", ")
                sendMessage("📃 白名单群: $white\n🚫 黑名单群: $black")
            }

            else -> sendMessage("❌ 未知配置项：$option，请输入 /bvp 查看帮助")
        }
    }

    private suspend fun CommandSender.updateBoolConfig(name: String, value: String?, setter: (Boolean) -> Unit) {
        if (value == null) {
            sendMessage("❌ 请提供值，例如: /bvp $name true")
            return
        }
        val boolValue = value.toBooleanStrictOrNull() ?: value.toBoolean()
        if (boolValue == null) {
            sendMessage("❌ 无效的布尔值：$value，应为 true/false 或 1/0")
        } else {
            setter(boolValue)
            Config.forceSave()
            sendMessage("✅ 配置已更新：$name = $boolValue")
        }
    }

    private suspend fun CommandSender.modifyAdmin(value: String?, add: Boolean) {
        val qq = value?.toLongOrNull()
        if (qq == null || qq <= 0) {
            sendMessage("❌ 无效的 QQ 号")
            return
        }
        if (add) {
            if (Config.isAdmin(qq)) {
                sendMessage("❌ QQ $qq 已是管理员")
            } else {
                Config.adminQQs.add(qq)
                Config.forceSave()
                sendMessage("✅ 已添加管理员: $qq")
            }
        } else {
            if (!Config.isAdmin(qq)) {
                sendMessage("❌ QQ $qq 不在管理员列表")
            } else if (Config.adminQQs.size <= 1) {
                sendMessage("❌ 无法移除最后一个管理员")
            } else {
                Config.adminQQs.remove(qq)
                Config.forceSave()
                sendMessage("✅ 已移除管理员: $qq")
            }
        }
    }

    private suspend fun CommandSender.modifyGroupList(value: String?, whitelist: Boolean, add: Boolean) {
        val groupId = value?.toLongOrNull()
        if (groupId == null) {
            sendMessage("❌ 无效群号")
            return
        }

        val list = if (whitelist) Config.groupWhiteList else Config.groupBlackList
        val other = if (whitelist) Config.groupBlackList else Config.groupWhiteList

        if (add) {
            list.add(groupId)
            other.remove(groupId)
            sendMessage("✅ 已将群 $groupId 添加至${if (whitelist) "白" else "黑"}名单")
        } else {
            if (list.remove(groupId)) {
                sendMessage("✅ 已将群 $groupId 移出${if (whitelist) "白" else "黑"}名单")
            } else {
                sendMessage("⚠️ 群 $groupId 不在${if (whitelist) "白" else "黑"}名单中")
            }
        }

        Config.forceSave()
    }
}