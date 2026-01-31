/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.media.MediaMetadata
import kotlinx.serialization.Serializable

object MediaMetadataCache {
    private val map = mutableMapOf<String, Metadata>()

    fun save(metadata: MediaMetadata): Metadata? {
        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (id.isBlank()) return null
        if (map.containsKey(id)) {
            return map[id]
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val data = Metadata(id, title, artist, duration)
        map[id] = data
        return data
    }

    fun get(id: String): Metadata? = map[id]
}

@Serializable
data class Metadata(
    val id: String,
    val title: String?,
    val artist: String?,
    val duration: Long
)