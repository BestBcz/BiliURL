package com.bcz.bilivideoparser

import com.google.gson.JsonObject
import java.io.IOException

internal data class BiliPlayback(
    val quality: Int,
    val videoUrls: List<String>,
    val audioUrls: List<String> = emptyList(),
    val dash: Boolean = false
)

internal object VideoQuality {
    val names = linkedMapOf(
        16 to "360P", 32 to "480P", 64 to "720P", 74 to "720P60", 80 to "1080P",
        112 to "1080P+", 116 to "1080P60", 120 to "4K", 127 to "8K"
    )
    fun name(quality: Int) = names[quality] ?: quality.toString()
}

internal object DashPlaybackParser {
    fun parse(data: JsonObject, requested: Int): BiliPlayback {
        val dash = data.get("dash")?.takeIf { it.isJsonObject }?.asJsonObject ?: throw IOException("播放接口未返回 DASH")
        val videos = dash.get("video")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty().map { it.asJsonObject }
            .filter { stream ->
                val id = stream.get("id")?.asInt ?: 0
                id in VideoQuality.names && id <= requested && urls(stream).isNotEmpty()
            }
        val video = videos.sortedWith(compareByDescending<JsonObject> { it["id"].asInt }
            .thenBy { when (it.get("codecid")?.asInt) { 7 -> 0; 12 -> 1; else -> 2 } }
            .thenByDescending { it.get("bandwidth")?.asLong ?: 0 }).firstOrNull()
            ?: throw IOException("没有账号可用且符合所选清晰度的 DASH 视频流")
        val audio = dash.get("audio")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty().map { it.asJsonObject }
            .filter { urls(it).isNotEmpty() }.maxByOrNull { it.get("bandwidth")?.asLong ?: 0 }
        return BiliPlayback(video["id"].asInt, urls(video), audio?.let(::urls).orEmpty(), dash = true)
    }

    fun urls(stream: JsonObject): List<String> {
        val result = mutableListOf<String>()
        for (name in listOf("baseUrl", "base_url", "url")) {
            stream.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.let(result::add)
        }
        for (name in listOf("backupUrl", "backup_url")) {
            stream.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.forEach {
                if (it.isJsonPrimitive) result.add(it.asString)
            }
        }
        return result.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
    }
}
