/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import com.highcapable.kavaref.extension.makeAccessible
import kotlinx.serialization.Serializable
import java.lang.reflect.Method

/**
 * 媒体元数据反射缓存工具。
 * * 负责通过反射从网易云音乐内部对象中提取歌曲信息。
 */
object MediaMetadataCache {
    // 使用 LRU 策略缓存最近的歌曲信息
    private val metadataCache = object : LinkedHashMap<String, Metadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Metadata>?): Boolean =
            size > 50
    }

    private var getIdMethod: Method? = null
    private var getMusicNameMethod: Method? = null
    private var getArtistsNameMethod: Method? = null
    private var getDurationMethod: Method? = null

    /**
     * 安全反射调用辅助方法。
     */
    private fun Method?.invokeSafe(any: Any): Any? {
        return try {
            this?.invoke(any)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析元数据对象并存入缓存。
     * * @param bizMusicMeta 网易云音乐内部 BizMusicMeta 对象实例。
     */
    fun putAndGet(bizMusicMeta: Any): Metadata? {
        val javaClass = bizMusicMeta.javaClass

        getIdMethod = javaClass.getDeclaredMethod("getId").apply { makeAccessible() }
        getMusicNameMethod =
            javaClass.getDeclaredMethod("getMusicName").apply { makeAccessible() }
        getArtistsNameMethod =
            javaClass.getDeclaredMethod("getArtistsName").apply { makeAccessible() }
        getDurationMethod =
            javaClass.getDeclaredMethod("getDuration").apply { makeAccessible() }

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