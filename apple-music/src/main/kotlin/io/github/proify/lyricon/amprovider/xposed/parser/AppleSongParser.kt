/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.parser

import io.github.proify.lyricon.amprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.amprovider.xposed.model.AppleSong
import io.github.proify.lyricon.amprovider.xposed.parser.LyricsSectionParser.mergeLyrics

object AppleSongParser {

    fun parser(songNative: Any): AppleSong = AppleSong().apply {
        adamId = callMethod(songNative, "getAdamId").toString()

        callMethod(songNative, "getAgents")?.let {
            agents = LyricsAgentParser.parserAgentVector(it)
        }

        duration = callMethod(songNative, "getDuration") as? Int ?: 0

        // language = get(o, "getLanguage") as? String
        // lyricsId = get(o, "getLyricsId") as? String
        // queueId = get(o, "getQueueId") as? Long ?: 0L

        val sections = callMethod(songNative, "getSections")
        if (sections != null) {
            lyrics = LyricsSectionParser.parserSectionVector(sections).mergeLyrics()
        }

        // timing = get(o, "getTiming") as? Long ?: 0L
        // timingName = get(o, "getAvailableTiming")?.name()
        // translation = get(o, "getTranslation") as? String
        // translationLanguages = StringVectorParser.parserStringVectorNative(get(o, "getTranslationLanguages"))

        // ★ 歌名/歌手优先级：
        // 1) 直接从原生 Song 对象获取（getTitle/getName/getArtist 等，最可靠、与 MediaSession 无关）
        // 2) 从 MediaMetadataCache 通过 adamId 查（兼容 adamId 与 mediaId 不一致）
        name = callMethod(songNative, "getTitle")?.toString()
            ?: callMethod(songNative, "getName")?.toString()
            ?: callMethod(songNative, "getTitleText")?.toString()
        artist = callMethod(songNative, "getArtist")?.toString()
            ?: callMethod(songNative, "getArtistName")?.toString()

        if (name == null || artist == null) {
            adamId?.let {
                // 优先用 adamId 直接查 MediaMetadata；
                // 若查不到（adamId 与 mediaId 不一致），
                // 则尝试通过 adamId 反查 mediaId 对应的 Metadata。
                MediaMetadataCache.getMetadataByIdOrAdamId(it)
                    ?.let { metadata ->
                        name = name ?: metadata.title
                        artist = artist ?: metadata.artist
                    }
            }
        }
    }
}
