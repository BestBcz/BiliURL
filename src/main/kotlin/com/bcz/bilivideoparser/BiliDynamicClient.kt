package com.bcz.bilivideoparser

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.CancellationException

/** Opus webpages and the dynamic API have different schemas; decode each explicitly. */
internal class BiliDynamicClient(
    private val request: (String) -> String,
    private val warning: (String) -> Unit = {},
    private val info: (String) -> Unit = {}
) {
    fun getDynamic(dynamicId: String, qqAppMessage: String? = null): BiliDynamicParser.BiliDynamicResult? {
        require(dynamicId.matches(Regex("[0-9]+"))) { "动态 ID 无效" }
        val fallbackTitle = shareTitle(qqAppMessage)
        val sources = listOf(
            "网页" to "https://www.bilibili.com/opus/$dynamicId",
            "动态 API" to "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=$dynamicId&features=itemOpusStyle,opusBigCover"
        )
        for ((name, url) in sources) {
            try {
                val response = request(url)
                val result = if (name == "网页") DynamicContentDecoder.fromHtml(dynamicId, response, fallbackTitle)
                    else DynamicContentDecoder.fromApi(dynamicId, response, fallbackTitle)
                if (result != null) {
                    info("[DynamicParser] $name 解析成功: id=$dynamicId, 图片=${result.pictures.size}")
                    return result
                }
                warning("[DynamicParser] $name 未返回可发送的正文或图片: id=$dynamicId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warning("[DynamicParser] $name 解析失败: id=$dynamicId, ${e.message}")
            }
        }
        return null
    }

    private fun shareTitle(message: String?): String {
        if (message.isNullOrBlank()) return ""
        return try {
            val meta = JsonParser.parseString(message).asJsonObject.obj("meta")
            meta?.obj("news")?.text("title")?.takeIf { it.isNotBlank() }
                ?: meta?.obj("detail_1")?.text("desc").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}

internal object DynamicContentDecoder {
    fun fromHtml(id: String, html: String, fallbackTitle: String = ""): BiliDynamicParser.BiliDynamicResult? {
        val match = Regex("""(?:window\.)?__INITIAL_STATE__\s*=\s*""").find(html) ?: return null
        val state = JsonReader(StringReader(html.substring(match.range.last + 1))).use {
            JsonParser.parseReader(it).asJsonObject
        }
        val detail = state.obj("detail") ?: return null
        val item = detail.obj("item") ?: detail
        return when {
            item.get("modules")?.isJsonArray == true -> opus(id, item, fallbackTitle)
            item.get("modules")?.isJsonObject == true -> dynamic(id, item, fallbackTitle)
            else -> null
        }
    }

    fun fromApi(id: String, response: String, fallbackTitle: String = ""): BiliDynamicParser.BiliDynamicResult? {
        val json = JsonParser.parseString(response).asJsonObject
        val code = json.get("code")?.asInt ?: throw IOException("动态接口缺少状态码")
        if (code != 0) throw IOException("动态接口 code=$code")
        val data = json.obj("data") ?: return null
        if (data.has("v_voucher")) throw IOException("动态接口要求风控验证 (v_voucher)")
        val item = data.obj("item") ?: return null
        return dynamic(id, item, fallbackTitle)
    }

    private fun opus(id: String, item: JsonObject, fallbackTitle: String): BiliDynamicParser.BiliDynamicResult? {
        if (!matchesId(id, item)) return null
        val modules = item.array("modules").mapNotNull { it.asObject() }
        val author = modules.firstNotNullOfOrNull { it.obj("module_author") }
        val title = modules.firstNotNullOfOrNull { it.obj("module_title")?.text("text")?.takeIf(String::isNotBlank) }
            ?: item.obj("basic")?.text("title")?.removeSuffix(" - 哔哩哔哩").orEmpty()
        val paragraphs = modules.flatMap { it.obj("module_content")?.array("paragraphs")?.toList().orEmpty() }
        val pictures = mutableListOf<String>()
        val lines = paragraphs.mapNotNull { element ->
            val paragraph = element.asObject() ?: return@mapNotNull null
            paragraph.obj("pic")?.array("pics")?.forEach { pic ->
                pic.asObject()?.let { pictures.add(it.text("url").ifBlank { it.text("src") }) }
            }
            paragraph.obj("text")?.array("nodes")?.joinToString("") { node ->
                val value = node.asObject()
                value?.obj("word")?.text("words")
                    ?: value?.obj("rich")?.let { it.text("text").ifBlank { it.text("orig_text") } }
                    ?: value?.obj("user")?.text("name")
                    ?: ""
            }
        }
        return result(id, author, title.ifBlank { fallbackTitle }, lines.joinToString("\n"), pictures)
    }

    private fun dynamic(id: String, item: JsonObject, fallbackTitle: String = "", depth: Int = 0): BiliDynamicParser.BiliDynamicResult? {
        if (depth > 3 || !matchesId(id, item) || item.get("visible")?.let { it.isJsonPrimitive && !it.asBoolean } == true) return null
        val modules = item.obj("modules") ?: return null
        val author = modules.obj("module_author")
        val module = modules.obj("module_dynamic") ?: return null
        val desc = module.obj("desc")
        var body = desc?.text("text").orEmpty().ifBlank {
            desc?.array("rich_text_nodes")?.joinToString("") { node ->
                node.asObject()?.let { it.text("text").ifBlank { it.text("orig_text") } }.orEmpty()
            }.orEmpty()
        }
        var title = ""
        val pictures = mutableListOf<String>()
        val major = module.obj("major")
        // All known major keys may be present with null values. Select the actual object.
        val opus = major?.obj("opus")
        val draw = major?.obj("draw")
        val article = major?.obj("article")
        val archive = major?.obj("archive")
        when {
            opus != null -> {
                title = opus.text("title")
                body = opus.obj("summary")?.text("text")?.takeIf(String::isNotBlank) ?: body
                opus.array("pics").forEach { pic -> pic.asObject()?.let { pictures.add(it.text("url").ifBlank { it.text("src") }) } }
            }
            draw != null -> draw.array("items").forEach { pic -> pic.asObject()?.let { pictures.add(it.text("src").ifBlank { it.text("url") }) } }
            article != null -> {
                title = article.text("title")
                val summary = article.text("desc").ifBlank { article.text("summary") }
                body = listOf(body, summary).filter { it.isNotBlank() }.distinct().joinToString("\n")
                article.array("covers").forEach { pic -> if (pic.isJsonPrimitive) pictures.add(pic.asString) }
            }
            archive != null -> {
                val video = "投稿了视频：${archive.text("title")}" + archive.text("bvid").takeIf(String::isNotBlank)
                    ?.let { "\nhttps://www.bilibili.com/video/$it" }.orEmpty()
                body = listOf(body, video).filter { it.isNotBlank() }.joinToString("\n\n")
                pictures.add(archive.text("cover"))
            }
        }
        item.obj("orig")?.let { original ->
            val originalId = original.text("id_str")
            if (originalId.isNotBlank()) dynamic(originalId, original, depth = depth + 1)?.let { forwarded ->
                body = listOf(body, "转发自 ${forwarded.userName}：\n${forwarded.content}").filter { it.isNotBlank() }.joinToString("\n\n")
                pictures.addAll(forwarded.pictures)
            }
        }
        return result(id, author, title.ifBlank { fallbackTitle }, body, pictures)
    }

    private fun result(id: String, author: JsonObject?, title: String, body: String, images: List<String>): BiliDynamicParser.BiliDynamicResult? {
        val pictures = images.mapNotNull(::imageUrl).distinct()
        val text = body.trim()
        val heading = title.trim()
        // A share-card title alone must not turn a failed parse into an apparent success.
        if (text.isBlank() && pictures.isEmpty()) return null
        val content = if (heading.isBlank()) text else buildString {
            append("标题：$heading")
            if (text.isNotBlank() && text != heading) append("\n内容：$text")
        }
        return BiliDynamicParser.BiliDynamicResult(id, author?.text("mid").orEmpty(),
            author?.text("name")?.takeIf(String::isNotBlank) ?: "未知作者", content, pictures,
            author?.text("pub_ts")?.toLongOrNull() ?: 0L)
    }

    private fun matchesId(id: String, item: JsonObject): Boolean = item.text("id_str").let { it.isBlank() || it == id }

    private fun imageUrl(value: String): String? {
        val url = value.trim()
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            url.startsWith("https://") -> url
            else -> null
        }
    }
}

private fun JsonElement.asObject(): JsonObject? = takeIf { isJsonObject }?.asJsonObject
private fun JsonObject.obj(name: String): JsonObject? = get(name)?.asObject()
private fun JsonObject.array(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
private fun JsonObject.text(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
