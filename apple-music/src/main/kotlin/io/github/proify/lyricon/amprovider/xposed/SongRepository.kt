/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.parser.AppleSongParser
import io.github.proify.lyricon.amprovider.xposed.util.toSong
import io.github.proify.lyricon.lyric.model.Song

object SongRepository {

    /**
     * 根据 ID 获取歌曲
     * 策略：磁盘缓存 -> 占位符
     *
     * 注意：id 可能是 MediaSession 的 mediaId，也可能是原生 Song 的 adamId。
     * 两者在某些场景下不一致，因此通过 MediaMetadataCache 查找
     * 歌名/歌手时需要同时兼容两种 ID。
     */
    fun getSong(id: String): Song {
        // 1. 尝试从磁盘缓存读取
        val cache = DiskSongManager.load(id)
        if (cache != null) {
            return cache.toSong()
        }

        // 2. 缓存未命中，从 Metadata 生成占位符（只有标题/歌手，无歌词）
        //    兼容 mediaId 与 adamId 两种 key（通过 getMetadataByIdOrAdamId 反查）
        val metadata = MediaMetadataCache.getMetadataByIdOrAdamId(id)
        return Song(id, metadata?.title, metadata?.artist)
    }

    /**
     * 保存解析好的歌曲到磁盘
     */
    fun saveSong(nativeSongObj: Any): Song? {
        val song = AppleSongParser.parser(nativeSongObj)
        if (song.adamId.isNullOrBlank()) {
            return null
        }
        DiskSongManager.save(song)
        return song.toSong()
    }
}