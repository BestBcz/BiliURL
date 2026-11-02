package com.bcz.bilivideoparser

import com.google.gson.JsonParser
import java.io.IOException
import kotlin.test.*

class DashPlaybackTest {
    private fun stream(id: Int, codec: Int, url: String) = """{"id":$id,"codecid":$codec,"baseUrl":"$url","backupUrl":["https://backup.example/$id.m4s"],"bandwidth":1000}"""
    private val data = JsonParser.parseString("""{"dash":{"video":[
        ${stream(80, 7, "https://cdn.example/1080.m4s")},
        ${stream(116, 12, "https://cdn.example/1080p60-hevc.m4s")},
        ${stream(116, 7, "https://cdn.example/1080p60-avc.m4s")},
        ${stream(120, 12, "https://cdn.example/4k.m4s")}
    ],"audio":[{"base_url":"https://cdn.example/audio.m4s","backup_url":null,"bandwidth":192000}]}}""").asJsonObject

    @Test
    fun `membership playback selects requested quality with AVC preference and an audio track`() {
        val selected = DashPlaybackParser.parse(data, 116)
        assertEquals(116, selected.quality)
        assertEquals("https://cdn.example/1080p60-avc.m4s", selected.videoUrls.first())
        assertEquals(listOf("https://cdn.example/audio.m4s"), selected.audioUrls)
        assertTrue(selected.dash)
    }

    @Test
    fun `playback never selects a quality above the configured target`() {
        assertEquals(80, DashPlaybackParser.parse(data, 112).quality)
        assertFailsWith<IOException> { DashPlaybackParser.parse(data, 64) }
    }

    @Test
    fun `DASH request preserves the part cid and asks for pc membership formats`() {
        val client = BiliApiClient(transport = { url ->
            if (url.endsWith("/nav")) throw IOException("nav unavailable")
            assertTrue(url.contains("cid=42489481519"))
            assertTrue(url.contains("fnval=4048"))
            assertTrue(url.contains("platform=pc"))
            assertTrue(url.contains("fourk=1"))
            """{"code":0,"data":$data}"""
        })
        assertEquals(120, client.getPlayback("BV1D3Hx6jEhD", 42489481519L, "120", true).quality)
    }

    @Test
    fun `without FFmpeg the client still requests single file MP4`() {
        val client = BiliApiClient(transport = { url ->
            if (url.endsWith("/nav")) throw IOException("nav unavailable")
            assertTrue(url.contains("fnval=1&"))
            """{"code":0,"data":{"format":"mp4","quality":16,"durl":[{"url":"https://cdn.example/video.mp4"}]}}"""
        })
        val selected = client.getPlayback("BV1D3Hx6jEhD", 42489481519L, "120", false)
        assertEquals(16, selected.quality)
        assertFalse(selected.dash)
    }
}
