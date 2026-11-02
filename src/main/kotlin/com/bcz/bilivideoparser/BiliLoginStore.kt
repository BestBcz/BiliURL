package com.bcz.bilivideoparser

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Credentials live in JSON under plugin data, never inside the YAML configuration. */
internal class BiliLoginStore(private val file: File) {
    data class State(val cookies: List<Cookie>, val refreshToken: String)

    fun load(): State? {
        if (!file.exists()) return null
        try {
            val json = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
            val cookies = json.getAsJsonArray("cookies").map { element ->
                val data = element.asJsonObject
                Cookie.Builder().name(data["name"].asString).value(data["value"].asString).apply {
                    if (data["hostOnly"].asBoolean) hostOnlyDomain(data["domain"].asString) else domain(data["domain"].asString)
                    path(data["path"].asString)
                    expiresAt(data["expiresAt"].asLong)
                    if (data["secure"].asBoolean) secure()
                    if (data["httpOnly"].asBoolean) httpOnly()
                }.build()
            }.filter { BiliHttpClient.isBilibiliHost(it.domain) && it.expiresAt > System.currentTimeMillis() }
            return State(cookies, json.get("refreshToken")?.asString.orEmpty())
        } catch (_: Exception) {
            throw IOException("登录会话文件无法读取，请重新执行 /bvp login")
        }
    }

    fun save(cookies: List<Cookie>, refreshToken: String) {
        val parent = file.absoluteFile.parentFile
        Files.createDirectories(parent.toPath())
        val json = JsonObject().apply {
            addProperty("refreshToken", refreshToken)
            add("cookies", JsonArray().also { array ->
                cookies.filter { BiliHttpClient.isBilibiliHost(it.domain) }.forEach { cookie ->
                    array.add(JsonObject().apply {
                        addProperty("name", cookie.name)
                        addProperty("value", cookie.value)
                        addProperty("domain", cookie.domain)
                        addProperty("path", cookie.path)
                        addProperty("expiresAt", cookie.expiresAt)
                        addProperty("hostOnly", cookie.hostOnly)
                        addProperty("secure", cookie.secure)
                        addProperty("httpOnly", cookie.httpOnly)
                    })
                }
            })
        }
        val temp = Files.createTempFile(parent.toPath(), "bili-session-", ".tmp")
        try {
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"))
            } catch (_: UnsupportedOperationException) {
                // Windows inherits the permissions of the plugin data directory.
            }
            Files.write(temp, json.toString().toByteArray(Charsets.UTF_8))
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    fun clear() {
        Files.deleteIfExists(file.toPath())
    }
}
