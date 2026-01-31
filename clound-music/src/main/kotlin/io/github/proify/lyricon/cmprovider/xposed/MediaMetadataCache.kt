/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import com.highcapable.kavaref.extension.makeAccessible
import kotlinx.serialization.Serializable
import java.lang.reflect.Method

object MediaMetadataCache {
    private val metadataCache = object : LinkedHashMap<String, Metadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Metadata>?): Boolean =
            size > 50
    }

    private var getIdMethod: Method? = null
    private var getMusicNameMethod: Method? = null
    private var getArtistsNameMethod: Method? = null
    private var getDurationMethod: Method? = null

    private fun Method?.invokeSafe(any: Any): Any? {
        return try {
            this?.invoke(any)
        } catch (_: Exception) {
            null
        }
    }

    fun putAndGet(bizMusicMeta: Any): Metadata? {
        val javaClass = bizMusicMeta.javaClass

        getIdMethod = javaClass.getMethod("getId").apply { makeAccessible() }
        getMusicNameMethod =
            javaClass.getMethod("getMusicName").apply { makeAccessible() }
        getArtistsNameMethod =
            javaClass.getMethod("getArtistsName").apply { makeAccessible() }
        getDurationMethod =
            javaClass.getMethod("getDuration").apply { makeAccessible() }

        val id = getIdMethod.invokeSafe(bizMusicMeta) as? Long ?: return null
        val musicName = getMusicNameMethod.invokeSafe(bizMusicMeta) as? String
        val artistsName = getArtistsNameMethod.invokeSafe(bizMusicMeta) as? String
        val duration = getDurationMethod.invokeSafe(bizMusicMeta) as? Long ?: 0L

        val strId = id.toString()
        val newMetadata = Metadata(
            id = strId,
            title = musicName,
            artist = artistsName,
            duration = duration
        )

        synchronized(metadataCache) {
            metadataCache[strId] = newMetadata
        }
        return newMetadata
    }

    fun getMetadataById(mediaId: String): Metadata? =
        synchronized(metadataCache) { metadataCache[mediaId] }

    @Serializable
    data class Metadata(
        val id: String,
        val title: String?,
        val artist: String?,
        val duration: Long
    )
}