package com.bcz.bilivideoparser

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FfmpegMuxerTest {
    @Test
    fun `missing FFmpeg is detected without throwing`() {
        assertFalse(FfmpegMuxer("bili-test-nonexistent-ffmpeg-executable").available())
    }

    @Test
    fun `FFmpeg merges separate video and audio with paths containing spaces`() {
        val executable = System.getenv("BILIURL_TEST_FFMPEG").orEmpty()
        assumeTrue(executable.isNotBlank())
        val directory = Files.createTempDirectory("bili mux test ").toFile()
        val video = File(directory, "video track.mp4")
        val audio = File(directory, "audio track.m4a")
        val output = File(directory, "merged output.mp4")
        val log = File(directory, "probe.log")
        fun run(vararg args: String): Int {
            val process = ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "FFmpeg test process timed out")
                return process.exitValue()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        try {
            val muxer = FfmpegMuxer(executable)
            assertTrue(muxer.available())
            assertEquals(0, run("-y", "-f", "lavfi", "-i", "color=c=blue:s=160x90:r=10:d=0.5", "-an", "-c:v", "libx264", video.absolutePath))
            assertEquals(0, run("-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=0.5", "-c:a", "aac", audio.absolutePath))
            muxer.mux(video, audio, output)
            assertTrue(output.length() > 0)
            run("-hide_banner", "-i", output.absolutePath)
            val probe = log.readText()
            assertTrue(probe.contains("Video:"))
            assertTrue(probe.contains("Audio:"))
        } finally {
            listOf(video, audio, output, log).forEach { it.delete() }
            directory.delete()
        }
    }
}
