package com.bcz.bilivideoparser

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiliApiClientTest {
    private val bvId = "BV1D3Hx6jEhD"
    private val nav = """{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
    private val video = """{"bvid":"$bvId","title":"A {video}","desc":"test","owner":{"name":"UP"},"stat":{"view":1437},"pic":"https://i0.hdslb.com/cover.jpg","duration":135,"cid":42489481519,"pages":[{"page":1,"cid":42489481519,"duration":135}]}"""
    private val mp4 = """{"code":0,"data":{"format":"mp4","durl":[{"url":"https://cdn.example/video.mp4","backup_url":["https://backup.example/video.mp4"]}]}}"""

    @Test
    fun `WBI signature matches the published test vector`() {
        val key = WbiSigner.mixinKey("7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45")
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
        assertEquals(
            "bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4",
            WbiSigner.sign(mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"), key, 1702204169)
        )
    }

    @Test
    fun `WBI encodes Chinese and spaces and removes forbidden characters`() {
        val result = WbiSigner.sign(mapOf("bar" to "五一四", "foo" to "one one four!'()*", "w_rid" to "old"), "key", 1)
        assertTrue(result.startsWith("bar=%E4%BA%94%E4%B8%80%E5%9B%9B&foo=one%20one%20four&wts=1&"))
        assertFalse(result.contains("old"))
    }

    @Test
    fun `guest nav still supplies signing keys and signed view retains long cid`() {
        val requests = mutableListOf<String>()
        val client = BiliApiClient(transport = { url ->
            requests.add(url)
            if (url.endsWith("/nav")) nav else """{"code":0,"data":$video}"""
        })
        assertEquals(42489481519L, client.getVideoData(bvId)["cid"].asLong)
        client.getVideoData(bvId)
        assertEquals(1, requests.count { it.endsWith("/nav") })
        assertTrue(requests[1].contains("/wbi/view?bvid=$bvId&wts="))
        assertTrue(requests[1].contains("&w_rid="))
    }

    @Test
    fun `HTTP 412 on API endpoints falls back to webpage initial state`() {
        val warnings = mutableListOf<String>()
        val client = BiliApiClient(warning = warnings::add, transport = { url ->
            when {
                url.endsWith("/nav") -> nav
                url.startsWith("https://www.bilibili.com/video/") ->
                    """<script>window.__INITIAL_STATE__={"videoData":$video};(function(){console.log("ignored")})();</script>"""
                else -> throw IOException("HTTP 412")
            }
        })
        assertEquals("A {video}", client.getVideoData(bvId)["title"].asString)
        assertEquals(2, warnings.count { it.contains("412") })
    }

    @Test
    fun `nav failure still allows legacy view`() {
        val client = BiliApiClient(transport = { url ->
            if (url.endsWith("/nav")) throw IOException("HTTP 412")
            """{"code":0,"data":$video}"""
        })
        assertEquals(bvId, client.getVideoData(bvId)["bvid"].asString)
    }

    @Test
    fun `code zero with verification voucher is not valid metadata`() {
        val client = BiliApiClient(transport = { url ->
            when {
                url.endsWith("/nav") -> nav
                url.contains("/wbi/view") -> """{"code":0,"data":{"v_voucher":"verification"}}"""
                else -> """{"code":0,"data":$video}"""
            }
        })
        assertEquals(bvId, client.getVideoData(bvId)["bvid"].asString)
    }

    @Test
    fun `all sources failing reports the error instead of empty details`() {
        val client = BiliApiClient(transport = { throw IOException("HTTP 412") })
        assertTrue(assertFailsWith<IOException> { client.getVideoData(bvId) }.message!!.contains("HTTP 412"))
    }

    @Test
    fun `webpage for another video cannot become the selected video`() {
        val client = BiliApiClient(transport = { url ->
            if (url.contains("api.bilibili.com")) throw IOException("HTTP 412")
            """<script>window.__INITIAL_STATE__={"videoData":${video.replace(bvId, "BV17x411w7KC")}};</script>"""
        })
        assertFailsWith<IOException> { client.getVideoData(bvId) }
    }

    @Test
    fun `signed playback requests MP4 and returns CDN mirrors`() {
        val client = BiliApiClient(transport = { url ->
            if (url.endsWith("/nav")) nav else {
                assertTrue(url.contains("cid=42489481519"))
                assertTrue(url.contains("fnval=1"))
                assertTrue(url.contains("platform=html5"))
                assertTrue(url.contains("w_rid="))
                mp4
            }
        })
        assertEquals(listOf("https://cdn.example/video.mp4", "https://backup.example/video.mp4"),
            client.getPlaybackUrls(bvId, 42489481519L, "16"))
    }

    @Test
    fun `null backup_url from the current API still permits the main MP4`() {
        val client = BiliApiClient(transport = { url ->
            if (url.endsWith("/nav")) nav else
                """{"code":0,"data":{"format":"mp4","durl":[{"url":"https://cdn.example/video.mp4","backup_url":null}]}}"""
        })
        assertEquals(listOf("https://cdn.example/video.mp4"), client.getPlaybackUrls(bvId, 42489481519L, "16"))
    }

    @Test
    fun `DASH only quality falls back to a complete lower quality MP4`() {
        val requests = mutableListOf<String>()
        val client = BiliApiClient(transport = { url ->
            requests.add(url)
            when {
                url.endsWith("/nav") -> nav
                url.contains("qn=80") -> """{"code":0,"data":{"format":"dash","durl":null,"dash":{"video":[],"audio":[]}}}"""
                else -> mp4
            }
        })
        assertEquals(2, client.getPlaybackUrls(bvId, 42489481519L, "80").size)
        assertTrue(requests.any { it.contains("qn=16") })
    }

    @Test
    fun `multi segment FLV is rejected rather than downloading only the first segment`() {
        val client = BiliApiClient(transport = { url ->
            if (url.endsWith("/nav")) nav else
                """{"code":0,"data":{"format":"flv","durl":[{"url":"https://cdn.example/1.flv"},{"url":"https://cdn.example/2.flv"}]}}"""
        })
        assertFailsWith<IOException> { client.getPlaybackUrls(bvId, 42489481519L, "16") }
    }
}
