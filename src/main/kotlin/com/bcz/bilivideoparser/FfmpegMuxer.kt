package com.bcz.bilivideoparser

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class FfmpegMuxer(private val executable: String = "ffmpeg") {
    fun available(): Boolean {
        val log = File.createTempFile("bili-ffmpeg-check-", ".log")
        try {
            val process = ProcessBuilder(executable, "-version").redirectErrorStream(true).redirectOutput(log).start()
            return try {
                process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } catch (_: IOException) {
            return false
        } finally {
            log.delete()
        }
    }

    fun mux(video: File, audio: File?, destination: File) {
        val log = File.createTempFile("ffmpeg-", ".log", destination.absoluteFile.parentFile)
        val arguments = mutableListOf(executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", video.absolutePath)
        if (audio != null) arguments.addAll(listOf("-i", audio.absolutePath))
        arguments.addAll(listOf("-map", "0:v:0"))
        if (audio != null) arguments.addAll(listOf("-map", "1:a:0"))
        arguments.addAll(listOf("-c", "copy", "-movflags", "+faststart", destination.absolutePath))
        try {
            val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log).start()
            try {
                if (!process.waitFor(300, TimeUnit.SECONDS)) throw IOException("FFmpeg 合并超时")
                if (process.exitValue() != 0 || !destination.isFile || destination.length() == 0L) {
                    throw IOException("FFmpeg 合并失败：${log.readText().takeLast(600)}")
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            log.delete()
        }
    }
}
