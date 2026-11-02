package com.bcz.bilivideoparser

import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.*

class BiliDynamicClientTest {
    private val id = "1259705064636809218"
    private val opus = fixture("reported-opus-state.json")
    private val article = fixture("reported-article-detail.json")
    private fun fixture(name: String) = javaClass.getResource("/dynamic/$name")!!.readText()
    private fun html(state: String) = "<script>window.__INITIAL_STATE__ =\n$state;\n(function(){unrelatedJavascript()})();</script>"
    private fun api(major: String, desc: String = "动态正文", uid: String = "1234567890123456789") = """{
        "code":0,"data":{"item":{"id_str":"$id","modules":{
        "module_author":{"name":"作者","mid":$uid,"pub_ts":"1792136341"},
        "module_dynamic":{"desc":{"text":"$desc"},"major":$major}}}}} """

    @Test
    fun `reported Opus modules array contains the article title body and two original images`() {
        val result = assertNotNull(DynamicContentDecoder.fromHtml(id, html(opus)))
        assertEquals(id, result.dynamicId)
        assertEquals("2067524167", result.uid)
        assertEquals("老莉莉12", result.userName)
        assertEquals(1792136341L, result.timestamp)
        assertTrue(result.content.replace(Regex("\\s+"), "").contains("肯德基新品肉霸堡是真的好吃阿！"))
        assertTrue(result.content.contains("bro！"))
        assertEquals(listOf(
            "https://i0.hdslb.com/bfs/new_dyn/c4680065fbc4727f1e445d963ebc94c42067524167.jpg",
            "https://i0.hdslb.com/bfs/new_dyn/df5b25a3f326cf790d4f72dbb71d77ff2067524167.jpg"
        ), result.pictures)
    }

    @Test
    fun `reported article API with null Opus fields returns its title summary and cover`() {
        val result = assertNotNull(DynamicContentDecoder.fromApi(id, article))
        assertTrue(result.content.contains("肯德基新品肉霸堡是真的好吃阿！"))
        assertTrue(result.content.contains("bro！"))
        assertEquals(1, result.pictures.size)
        assertTrue(result.pictures.first().contains("/new_dyn/banner/"))
    }

    @Test
    fun `successful webpage parsing does not request the removed legacy endpoint or dump HTML`() {
        val requests = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val client = BiliDynamicClient(request = { url -> requests.add(url); html(opus) }, warning = logs::add, info = logs::add)
        val result = assertNotNull(client.getDynamic(id))
        assertEquals(2, result.pictures.size)
        assertEquals(listOf("https://www.bilibili.com/opus/$id"), requests)
        assertTrue(logs.single().contains("解析成功"))
        assertFalse(logs.any { it.contains("__INITIAL_STATE__") || it.contains("<script>") })
    }

    @Test
    fun `webpage failure falls back to the modern API with Opus features`() {
        val requests = mutableListOf<String>()
        val client = BiliDynamicClient(request = { url ->
            requests.add(url)
            if (url.contains("/opus/")) throw IOException("HTTP 412") else article
        })
        assertTrue(assertNotNull(client.getDynamic(id)).content.contains("bro！"))
        assertEquals(2, requests.size)
        assertTrue(requests.last().contains("features=itemOpusStyle,opusBigCover"))
        assertFalse(requests.any { it.contains("api.vc.bilibili.com") })
    }

    @Test
    fun `null major fields do not hide a draw and long numeric UIDs stay exact`() {
        val result = assertNotNull(DynamicContentDecoder.fromApi(id, api("""{
            "opus":null,"archive":null,"article":null,"draw":{"items":[{"src":"//i0.hdslb.com/photo.jpg"}]}}
        """)))
        assertEquals("1234567890123456789", result.uid)
        assertEquals("动态正文", result.content)
        assertEquals(listOf("https://i0.hdslb.com/photo.jpg"), result.pictures)
    }

    @Test
    fun `video posts preserve the video title link and cover despite null Opus and draw`() {
        val result = assertNotNull(DynamicContentDecoder.fromApi(id, api("""{
            "opus":null,"draw":null,"article":null,"archive":{"title":"视频标题","bvid":"BV1D3Hx6jEhD","cover":"https://i0.hdslb.com/cover.jpg"}}
        """)))
        assertTrue(result.content.contains("视频标题"))
        assertTrue(result.content.contains("https://www.bilibili.com/video/BV1D3Hx6jEhD"))
        assertEquals(1, result.pictures.size)
    }

    @Test
    fun `Opus major reads summary and deduplicates normalized images`() {
        val result = assertNotNull(DynamicContentDecoder.fromApi(id, api("""{
            "opus":{"title":"图文标题","summary":{"text":"正文"},"pics":[
                {"url":"http://i0.hdslb.com/photo.jpg"},{"src":"https://i0.hdslb.com/photo.jpg"}]},"draw":null}
        """)))
        assertEquals("标题：图文标题\n内容：正文", result.content)
        assertEquals(listOf("https://i0.hdslb.com/photo.jpg"), result.pictures)
    }

    @Test
    fun `API and webpage author-only responses cannot become successful empty messages`() {
        assertNull(DynamicContentDecoder.fromApi(id, api("null", desc = ""), "分享标题"))
        assertNull(DynamicContentDecoder.fromHtml(id, html("""{"detail":{"modules":[
            {"module_author":{"name":"作者","mid":"123"}}, {"module_title":{"text":"仅标题"}}
        ]}}"""), "分享标题"))
        assertNull(BiliDynamicClient(request = { if (it.contains("/opus/")) "<html/>" else api("null", desc = "") }).getDynamic(id))
    }

    @Test
    fun `API error codes and verification vouchers are failures even with data`() {
        assertTrue(assertFailsWith<IOException> {
            DynamicContentDecoder.fromApi(id, """{"code":-352,"data":{}}""")
        }.message!!.contains("-352"))
        assertFailsWith<IOException> {
            DynamicContentDecoder.fromApi(id, """{"code":0,"data":{"v_voucher":"verification"}}""")
        }
    }

    @Test
    fun `webpage and API IDs must match the requested dynamic`() {
        assertNull(DynamicContentDecoder.fromHtml("999", html(opus)))
        assertNull(DynamicContentDecoder.fromApi("999", article))
    }

    @Test
    fun `older nested dynamic initial state still parses`() {
        val data = JsonParser.parseString(api("null")).asJsonObject.getAsJsonObject("data")
        val result = assertNotNull(DynamicContentDecoder.fromHtml(id, html("""{"detail":$data}""")))
        assertEquals("动态正文", result.content)
    }

    @Test
    fun `forwarded dynamics include the original text and pictures`() {
        val outer = JsonParser.parseString(api("null")).asJsonObject
        val original = JsonParser.parseString(api("""{"draw":{"items":[{"src":"//i0.hdslb.com/original.jpg"}]}}""", "原动态内容"))
            .asJsonObject.getAsJsonObject("data").getAsJsonObject("item")
        original.addProperty("id_str", "999")
        outer.getAsJsonObject("data").getAsJsonObject("item").add("orig", original)
        val result = assertNotNull(DynamicContentDecoder.fromApi(id, outer.toString()))
        assertTrue(result.content.contains("原动态内容"))
        assertTrue(result.content.contains("转发自"))
        assertEquals(1, result.pictures.size)
    }

    @Test
    fun `cancelled parsing does not continue to another endpoint`() {
        var requests = 0
        val client = BiliDynamicClient(request = { requests++; throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { client.getDynamic(id) }
        assertEquals(1, requests)
    }
}
