/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed.api

import android.app.AndroidAppHelper
import android.content.Context
import io.github.proify.lyricon.spotifyprovider.xposed.api.response.LyricResponse
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

object SpotifyApi {
    private val CACHE_EXPIRATION_MILLIS = TimeUnit.HOURS.toMillis(24)
    private const val MAX_RETRIES = 3

    val headers = mutableMapOf<String, String>()

    private val jsonParser: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        prettyPrint = true
        coerceInputValues = true
    }

    @Throws(Exception::class)
    fun fetchLyricResponse(id: String): LyricResponse {
        val rawJson = fetchRawLyric(id)
        return jsonParser.decodeFromString<LyricResponse>(rawJson)
    }

    @Throws(Exception::class)
    fun fetchRawLyric(id: String): String {
        val context: Context? = AndroidAppHelper.currentApplication()

        // 1. 请求前尝试读取未过期的缓存
        val cachedData = Cache.readCachedLyric(context, id, maxAge = CACHE_EXPIRATION_MILLIS)
        if (cachedData != null) {
            return cachedData
        }

        // 2. 带有重试机制的网络请求
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                return performNetworkRequest(id, context)
            } catch (e: NoFoundLyricException) {
                // 如果是 404 或明确没歌词，没必要重试，直接抛出
                throw e
            } catch (e: Exception) {
                lastException = e
                // 如果还没到最后一次尝试，可以稍微等待或直接进入下次循环
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(500L * attempt) // 简单的退避策略
                    continue
                }
            }
        }

        // 3. 所有尝试都失败后的兜底：即使过期了也尝试读一下缓存
        return Cache.readCachedLyric(context, id, maxAge = Long.MAX_VALUE)
            ?: throw (lastException
                ?: IOException("Failed to fetch lyric after $MAX_RETRIES attempts"))
    }

    /**
     * 执行单次网络请求的具体逻辑
     */
    @Throws(Exception::class)
    private fun performNetworkRequest(id: String, context: Context?): String {
        val urlString = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$id"
        val url = URI.create(urlString).toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            setRequestProperty("accept", "application/json")
            setRequestProperty("app-platform", "WebPlayer")
        }

        return try {
            when (val responseCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val rawJson = connection.inputStream.bufferedReader().use { it.readText() }
                    // 成功后写入缓存
                    Cache.saveLyric(context, id, rawJson)
                    rawJson
                }

                HttpURLConnection.HTTP_INTERNAL_ERROR, HttpURLConnection.HTTP_NOT_FOUND ->
                    throw NoFoundLyricException(id, "Lyric not found for $id")

                else -> throw IOException("HTTP Error: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private object Cache {
        fun readCachedLyric(context: Context?, id: String, maxAge: Long): String? {
            if (context == null) return null
            val cacheFile = getCacheFile(context, id)
            if (!cacheFile.exists()) return null

            val lastModified = cacheFile.lastModified()
            val now = System.currentTimeMillis()

            return if (now - lastModified < maxAge) {
                cacheFile.readText()
            } else {
                null
            }
        }

        fun saveLyric(context: Context?, id: String, rawJson: String) {
            if (context == null) return
            val cacheFile = getCacheFile(context, id)
            try {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(rawJson)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun getCacheFile(context: Context, id: String) =
            File(getCacheDirectory(context), "$id.json")

        private fun getCacheDirectory(context: Context) =
            File(File(context.cacheDir, "lyricon_lyric"), Locale.getDefault().toLanguageTag())
    }
}