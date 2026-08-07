/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.media.MediaMetadata
import kotlinx.serialization.Serializable

object MediaMetadataCache {
    // key = mediaId (来自 MediaSession METADATA_KEY_MEDIA_ID)
    private val metadataCache = object : LinkedHashMap<String, Metadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Metadata>?): Boolean =
            size > 100
    }

    // adamId -> mediaId 映射
    // 用于 Apple Song.adamId 与 MediaSession mediaId 不一致时，
    // 通过 adamId 反查 mediaId 对应的 Metadata（歌名/歌手/时长）
    private val adamIdToMediaId = HashMap<String, String>()

    fun putAndGet(metadata: MediaMetadata): Metadata? {
        val mediaId: String? = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        // Apple Music（以及很多播放器）有时只填 DISPLAY_TITLE / ALBUM_TITLE，
        // 不填 METADATA_KEY_TITLE。做多重兜底避免歌名显示为 null。
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        // ★ 关键：Apple Music 的 MediaMetadata 可能不设置 METADATA_KEY_MEDIA_ID！
        // 此时不能返回 null（否则 onSongChanged 永远不会被调用，切歌检测失效），
        // 需要用 title+artist 生成一个稳定的 fallback ID，保证 metadata 能被缓存。
        val effectiveId = if (mediaId.isNullOrBlank()) {
            "meta:${title ?: ""}:${artist ?: ""}:$duration".hashCode().toString()
        } else {
            mediaId
        }

        val newMetadata = Metadata(effectiveId, title, artist, duration)
        metadataCache[effectiveId] = newMetadata
        return newMetadata
    }

    fun getMetadataById(mediaId: String): Metadata? = metadataCache[mediaId]

    /**
     * 记录 adamId 与 mediaId 的映射关系。
     * 当 buildTimeRangeToLyricsMap 传来的 Song.getAdamId()
     * 与 MediaSession 的 METADATA_KEY_MEDIA_ID 不一致时，
     * 通过此映射即可用 adamId 反查出 MediaMetadata（歌名/歌手）。
     */
    fun putAdamIdMapping(adamId: String, mediaId: String) {
        if (adamId.isBlank() || mediaId.isBlank()) return
        adamIdToMediaId[adamId] = mediaId
        // 如果 mediaId 已有 metadata，将其也以 adamId 为 key 缓存一份
        metadataCache[mediaId]?.let { metadataCache[adamId] = it }
    }

    /**
     * 优先用 mediaId 查，若不存在则尝试通过 adamId 反查。
     */
    fun getMetadataByIdOrAdamId(id: String): Metadata? {
        metadataCache[id]?.let { return it }
        adamIdToMediaId[id]?.let { mediaId -> return metadataCache[mediaId] }
        return null
    }

    @Serializable
    data class Metadata(
        val id: String,
        val title: String?,
        val artist: String?,
        val duration: Long
    )
}