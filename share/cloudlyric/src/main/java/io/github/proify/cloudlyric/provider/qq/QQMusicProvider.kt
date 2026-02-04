/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.qq

import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.qrckit.QrcDownloader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

class QQMusicProvider : LyricsProvider {
    companion object {
        const val ID = "QQMusicProvider"
    }

    override val id: String = ID

    override suspend fun search(
        query: String?,
        trackName: String?,
        artistName: String?,
        albumName: String?,
        limit: Int
    ): List<LyricsResult> {
        val key = listOfNotNull(query, trackName, artistName, albumName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (key.isBlank()) return emptyList()

        val element =
            MusicApiService.searchWithKeyword(key, resultNum = limit) ?: return emptyList()

        val searchResponse = try {
            jsonParser.decodeFromJsonElement<QQSearchResponse>(element)
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        val take = searchResponse.list.take(limit)
        //  println("QQMusicProvider: ${take.size} ,source ${searchResponse.list.size}")
        return take.mapNotNull { song ->
            try {
                val response = QrcDownloader.downloadLyrics(song.id.toString())
                val richLyricLines = response.parsedLyric.richLyricLines

                LyricsResult(
                    trackName = song.name,
                    albumName = song.album?.name.orEmpty(),
                    artistName = song.singer.joinToString(", ") { it.name },
                    rich = richLyricLines
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    private data class QQSearchResponse(
        val list: List<QQSongItem> = emptyList()
    )

    @Serializable
    private data class QQSongItem(
        val id: Long,
        val name: String,
        val album: QQAlbum? = null,
        val singer: List<QQSinger> = emptyList()
    )

    @Serializable
    private data class QQAlbum(val name: String)

    @Serializable
    private data class QQSinger(val name: String)
}