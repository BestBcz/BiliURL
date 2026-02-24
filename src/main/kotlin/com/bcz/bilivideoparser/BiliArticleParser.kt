package com.bcz.bilivideoparser

import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

object BiliArticleParser {
    data class BiliArticleResult(
        val articleId: String,
        val authorName: String,
        val title: String,
        val summary: String,
        val cover: String?,
        val jumpUrl: String
    )

    fun extractArticleIdFromAnyUrl(url: String): String? {
        if (url.contains("b23.tv/")) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connect()
                val location = conn.getHeaderField("Location") ?: return null
                return extractArticleIdFromAnyUrl(location)
            } catch (_: Exception) {
                return null
            }
        }

        Regex("""bilibili\.com/read/cv(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("""bilibili\.com/read/mobile\?id=(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    fun parseArticle(url: String, qqAppMessage: String? = null): BiliArticleResult? {
        val articleId = extractArticleIdFromAnyUrl(url) ?: return null
        val apiUrl = "https://api.bilibili.com/x/article/viewinfo?id=$articleId&mobi_app=pc&from=web"

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                BiliVideoParser.logger.warning("[ArticleParser] API 返回错误码: ${connection.responseCode}")
                connection.disconnect()
                return null
            }

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            connection.disconnect()

            val json = Gson().fromJson(response, Map::class.java)
            val code = (json["code"] as? Double)?.toInt() ?: -1
            if (code != 0) {
                BiliVideoParser.logger.warning("[ArticleParser] API code 非 0: $code")
                return null
            }

            val data = json["data"] as? Map<*, *> ?: return null
            val title = data["title"] as? String ?: ""
            val authorName = data["author_name"] as? String ?: "未知作者"
            val description = data["description"] as? String ?: ""
            val summaryFromApi = (data["summary"] as? String)?.trim().orEmpty()

            val originImageUrls = data["origin_image_urls"] as? List<*>
            val firstCover = originImageUrls
                ?.mapNotNull { it as? String }
                ?.firstOrNull()
                ?: (data["banner_url"] as? String)

            val summary = when {
                summaryFromApi.isNotBlank() -> summaryFromApi
                description.isNotBlank() -> description
                !qqAppMessage.isNullOrBlank() -> extractSummaryFromQQApp(qqAppMessage)
                else -> ""
            }

            BiliArticleResult(
                articleId = articleId,
                authorName = authorName,
                title = title,
                summary = summary,
                cover = firstCover,
                jumpUrl = "https://www.bilibili.com/read/cv$articleId"
            )
        } catch (e: Exception) {
            BiliVideoParser.logger.warning("[ArticleParser] 解析异常: ${e.message}")
            null
        }
    }

    private fun extractSummaryFromQQApp(qqAppMessage: String): String {
        return try {
            val qqJson = Gson().fromJson(qqAppMessage, Map::class.java)
            val meta = qqJson["meta"] as? Map<*, *>
            val news = meta?.get("news") as? Map<*, *>
            (news?.get("desc") as? String ?: "").trim()
        } catch (_: Exception) {
            ""
        }
    }
}
