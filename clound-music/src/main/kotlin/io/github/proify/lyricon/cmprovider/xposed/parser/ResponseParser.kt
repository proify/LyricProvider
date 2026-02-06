/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed.parser

import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.cmprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.yrckit.YrcParser
import kotlinx.serialization.json.Json
import java.util.TreeMap
import kotlin.math.abs

private const val TRANSLATE_TIME_TOLERANCE = 50L

object ResponseParser {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parse(json: String): LyricResponse = this.json.decodeFromString(json)
}

fun LyricResponse.toSong(): Song {
    val idString = musicId.toString()
    val metadata = MediaMetadataCache.getMetadataById(idString)

    return Song(id = idString).apply {
        name = metadata?.title
        artist = metadata?.artist
        lyrics = toRichLines()
    }
}

fun LyricResponse.toRichLines(): List<RichLyricLine> {
    val sourceLines: List<LyricLine> = when {
        !yrc.isNullOrBlank() -> YrcParser.parse(yrc).ifEmpty {
            LrcParser.parse(lrc).lines
        }

        !lrc.isNullOrBlank() -> LrcParser.parse(lrc).lines.ifEmpty { YrcParser.parse(yrc) }
        else -> return emptyList()
    }

    //Log.d("LyricResponse", "Parsing lyrics for $sourceLines")

    val rawTranslate = yrcTranslateLyric.takeUnless { it.isNullOrBlank() }
        ?: lrcTranslateLyric.takeUnless { it.isNullOrBlank() }

    val translateMap = rawTranslate?.let {
        val lines = LrcParser.parse(it).lines
        TreeMap<Long, String>().apply {
            lines.forEach { line -> put(line.begin, line.text.orEmpty()) }
        }
    } ?: emptyMap()

    return sourceLines.map { line ->
        val translationText = if (translateMap is TreeMap) {
            findClosestTranslation(translateMap, line.begin)
        } else {
            null
        }

        RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = line.text,
            words = line.words,
            translation = translationText
        )
    }
}

/**
 * 在 [TreeMap] 中寻找与给定时间戳 [time] 最接近且在容差范围内的翻译。
 *
 * @param map 时间戳到文本的映射。
 * @param time 目标匹配时间（毫秒）。
 * @return 匹配到的翻译文本，若无匹配则返回 null。
 */
private fun findClosestTranslation(map: TreeMap<Long, String>, time: Long): String? {
    // 找到小于等于 time 的最大值
    val floor = map.floorEntry(time)
    // 找到大于等于 time 的最小值
    val ceil = map.ceilingEntry(time)

    val floorDiff = floor?.let { abs(it.key - time) } ?: Long.MAX_VALUE
    val ceilDiff = ceil?.let { abs(it.key - time) } ?: Long.MAX_VALUE

    return when {
        floorDiff <= ceilDiff && floorDiff <= TRANSLATE_TIME_TOLERANCE -> floor?.value
        ceilDiff <= TRANSLATE_TIME_TOLERANCE -> ceil?.value
        else -> null
    }
}