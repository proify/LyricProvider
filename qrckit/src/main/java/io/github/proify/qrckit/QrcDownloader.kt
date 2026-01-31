/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object QrcDownloader {
    private const val LYRIC_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    @Throws(Exception::class)
    fun downloadLyrics(
        musicId: String,
        userAgent: String = DEFAULT_USER_AGENT
    ): LyricResponse {
        val params = mapOf(
            "version" to "15",
            "miniversion" to "100",
            "lrctype" to "4",
            "musicid" to musicId
        )

        val postData = params.entries.joinToString("&") { (k, v) ->
            "${k}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val url = URI.create(LYRIC_URL).toURL()
        val conn = url.openConnection() as HttpURLConnection

        return try {
            conn.apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Referer", "https://y.qq.com/")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            conn.outputStream.use { it.write(postData.toByteArray(StandardCharsets.UTF_8)) }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                LyricResponse(musicId, raw)
            } else {
                throw IllegalStateException("HTTP 请求失败: ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}