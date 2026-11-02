package com.bcz.bilivideoparser

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

internal class BiliLoginService(
    private val http: BiliHttpClient,
    private val store: BiliLoginStore,
    private val scope: CoroutineScope,
    private val dataDirectory: File,
    private val warning: (String) -> Unit
) {
    private val api = BiliLoginApi(http::get)
    private val lock = Mutex()
    private var loginJob: Job? = null

    fun restore() {
        try {
            store.load()?.let { http.cookieJar.replaceAll(it.cookies) }
        } catch (e: Exception) {
            warning(e.message ?: "登录会话无法恢复，请重新扫码")
        }
    }

    suspend fun start(sender: CommandSender) {
        val job = lock.withLock {
            if (loginJob?.isCompleted == false) null else scope.launch(start = CoroutineStart.LAZY) { login(sender) }.also { loginJob = it }
        }
        if (job == null) sender.sendMessage("已有扫码登录正在进行，请先完成扫码或执行 /bvp logout 取消。") else job.start()
    }

    suspend fun status(sender: CommandSender) {
        try {
            val account = withContext(Dispatchers.IO) { api.account() }
            sender.sendMessage(if (!account.loggedIn) "当前未登录或登录已过期，请执行 /bvp login。" else
                "当前登录：${account.name} (UID ${account.uid})\n大会员：${if (account.vip) "已开通" else "未开通"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sender.sendMessage("登录状态检查失败：${e.message}。本地会话已保留。")
        }
    }

    suspend fun logout() {
        lock.withLock {
            loginJob?.cancelAndJoin()
            loginJob = null
            withContext(Dispatchers.IO) { store.clear() }
            Config.bilibiliCookie = ""
            http.clearSession()
        }
    }

    private suspend fun login(sender: CommandSender) {
        // Stage a fresh session so old manual tickets cannot contaminate a new QR login.
        // Keep the current account usable until the new session has been saved successfully.
        val loginHttp = BiliHttpClient(warning = warning)
        val loginApi = BiliLoginApi(loginHttp::get)
        var imageFile: File? = null
        try {
            val code = withContext(Dispatchers.IO) {
                loginHttp.prepareBrowserSession()
                loginApi.generate()
            }
            val png = withContext(Dispatchers.IO) {
                dataDirectory.mkdirs()
                File(dataDirectory, "login-qrcode.png").also {
                    imageFile = it
                    writeQrCode(code.url, it)
                }
            }
            val contact = sender.subject
            if (contact != null) {
                png.toExternalResource("png").use { sender.sendMessage(contact.uploadImage(it)) }
                sender.sendMessage("请使用哔哩哔哩 APP 扫码并确认登录，二维码 3 分钟内有效。")
            } else {
                sender.sendMessage("请打开二维码图片并使用哔哩哔哩 APP 扫码确认：${png.absolutePath}\n有效期 3 分钟。")
            }
            var scanned = false
            withTimeout(180_000) {
                while (isActive) {
                    delay(3000)
                    val result = withContext(Dispatchers.IO) { loginApi.poll(code.key) }
                    when (result.status) {
                        BiliLoginApi.Status.WAITING -> Unit
                        BiliLoginApi.Status.SCANNED -> if (!scanned) {
                            scanned = true
                            sender.sendMessage("已扫码，请在手机上确认登录。")
                        }
                        BiliLoginApi.Status.EXPIRED -> {
                            sender.sendMessage("二维码已失效，请重新执行 /bvp login。")
                            return@withTimeout
                        }
                        BiliLoginApi.Status.CONFIRMED -> {
                            withContext(Dispatchers.IO) {
                                val cookies = loginHttp.cookieJar.snapshot()
                                store.save(cookies, result.refreshToken)
                                Config.bilibiliCookie = ""
                                http.cookieJar.replaceAll(cookies)
                            }
                            sender.sendMessage("扫码登录成功，会话已保存，重启后自动恢复。")
                            status(sender)
                            return@withTimeout
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            sender.sendMessage("二维码已超时，请重新执行 /bvp login。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warning("扫码登录失败：${e.message}")
            sender.sendMessage("扫码登录失败：${e.message}")
        } finally {
            imageFile?.delete()
            loginHttp.close()
        }
    }

    companion object {
        internal fun writeQrCode(url: String, file: File) {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2))
            val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xffffff)
            }
            check(ImageIO.write(image, "png", file)) { "无法写入登录二维码图片" }
        }
    }
}
