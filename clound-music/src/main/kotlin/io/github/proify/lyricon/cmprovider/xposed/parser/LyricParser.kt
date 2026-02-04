/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.cmprovider.xposed.parser

import io.github.proify.lyricon.cmprovider.xposed.parser.model.LrcEntry
import io.github.proify.lyricon.cmprovider.xposed.parser.model.LyricInfo
import io.github.proify.lyricon.cmprovider.xposed.parser.model.LyricLine
import io.github.proify.lyricon.cmprovider.xposed.parser.model.LyricResponse
import io.github.proify.lyricon.cmprovider.xposed.parser.model.LyricWord
import io.github.proify.lyricon.cmprovider.xposed.parser.model.YrcEntry
import io.github.proify.lyricon.cmprovider.xposed.parser.model.YrcSyllable
import kotlinx.serialization.json.Json
import java.util.regex.Pattern
import kotlin.math.abs

object LyricParser {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val LRC_TIME_REGEX = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})([.:](\\d{1,3}))?]")
    private val YRC_LINE_HEADER_REGEX = Pattern.compile("\\[(\\d+),(\\d+)]")
    private val YRC_SYLLABLE_REGEX = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)")

    fun parseResponse(jsonString: String): LyricResponse {
        val response = jsonParser.decodeFromString<LyricResponse>(jsonString)
        return response
    }

    fun toLyricInfo(response: LyricResponse): LyricInfo {
        val info = LyricInfo(
            musicId = response.musicId,
            pureMusic = response.pureMusic
        )
        val lyrics = mutableListOf<LyricLine>()

        val lrc = parseLrc(response.lrc)
        val yrc = parseYrc(response.yrc)
        val lrcTranslateLyric = parseLrc(response.lrcTranslateLyric)
        //尝试以lrc格式解析
        val yrcTranslateLyric = parseLrc(response.yrcTranslateLyric)

        if (yrc.isNotEmpty()) {
            yrc.forEach { lyrics.add(it.toLyricLine()) }
        } else if (lrc.isNotEmpty()) {
            lrc.forEach { lyrics.add(it.toLyricLine()) }
        }

        attachTranslations(lyrics, yrcTranslateLyric, lrcTranslateLyric)
        info.lyrics = lyrics
        return info
    }

    private fun attachTranslations(lyrics: List<LyricLine>, vararg sources: List<LrcEntry>) {
        lyrics.forEach { line ->
            sources.forEach { source ->
                val translation =
                    source.firstOrNull { abs(it.start - line.start) < 100 }
                if (translation != null && translation.text.isNotEmpty()) {
                    line.translation = translation.text
                    return@forEach
                }
            }
        }
    }

    private fun YrcEntry.toLyricLine(): LyricLine {
        val line = LyricLine(
            start = start,
            end = end,
            duration = duration,
        )
        val words = mutableListOf<LyricWord>()

        syllables.forEach { syllable ->
            val word = LyricWord(
                start = syllable.start,
                end = syllable.end,
                duration = syllable.duration,
                text = syllable.text
            )
            words.add(word)
        }
        line.words = words
        line.text = words.joinToString("") { it.text.orEmpty() }
        return line
    }

    private fun LrcEntry.toLyricLine() = LyricLine(
        start = start,
        end = end,
        duration = duration,
        text = text
    )

    /**
     * 解析 LRC 并自动计算持续时间
     */
    fun parseLrc(raw: String?): List<LrcEntry> {
        val entries = mutableListOf<LrcEntry>()
        if (raw.isNullOrBlank()) return entries

        raw.lineSequence().forEach { line ->
            if (line.isBlank() || line.trim().startsWith("[").not()) return@forEach

            val matcher = LRC_TIME_REGEX.matcher(line)
            var lastEndIndex = 0
            val times = mutableListOf<Long>()

            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = matcher.group(4) ?: "0"
                val ms = msStr.padEnd(3, '0').take(3).toLong()
                times.add(min * 60000 + sec * 1000 + ms)
                lastEndIndex = matcher.end()
            }

            if (times.isNotEmpty()) {
                val content = line.substring(lastEndIndex).trim()
                if (content.isNotEmpty())
                    times.forEach { entries.add(LrcEntry(start = it, text = content)) }
            }
        }

        // 排序并计算 endTime 和 duration
        val sorted = entries.sortedBy { it.start }
        @Suppress("ReplaceManualRangeWithIndicesCalls")
        for (i in 0 until sorted.size) {
            val current = sorted[i]
            if (i < sorted.size - 1) {
                current.end = sorted[i + 1].start
            } else {
                // 最后一行默认持续 10 秒
                current.end = current.start + 10000
            }
            current.duration = current.end - current.start
        }
        return sorted
    }

    /**
     * 解析 YRC 包含行与字的完整时间信息
     */
    fun parseYrc(raw: String?): List<YrcEntry> {
        val entries = mutableListOf<YrcEntry>()
        if (raw.isNullOrBlank()) return entries

        raw.lineSequence().forEach { line ->
            val trimLine = line.trim()
            if (trimLine.isBlank() || trimLine.startsWith("{")) return@forEach

            val headerMatcher = YRC_LINE_HEADER_REGEX.matcher(trimLine)
            if (headerMatcher.find()) {
                val lineStart = headerMatcher.group(1)?.toLongOrNull() ?: 0L
                val lineDuration = headerMatcher.group(2)?.toLongOrNull() ?: 0L
                val lineEnd = lineStart + lineDuration

                val syllables = mutableListOf<YrcSyllable>()
                val contentPart = trimLine.substring(headerMatcher.end())
                val syllableMatcher = YRC_SYLLABLE_REGEX.matcher(contentPart)

                while (syllableMatcher.find()) {
                    val start = syllableMatcher.group(1)?.toLongOrNull() ?: 0L
                    val duration = syllableMatcher.group(2)?.toLongOrNull() ?: 0L
                    val text = syllableMatcher.group(3) ?: ""

                    if (text.isEmpty()) continue

                    syllables.add(
                        YrcSyllable(
                            start = start,
                            end = start + duration,
                            duration = duration,
                            text = text
                        )
                    )
                }

                val sortedSyllables = syllables.sortedBy { it.start }
                entries.add(YrcEntry(lineStart, lineEnd, lineDuration, sortedSyllables))
            }
        }

        val sorted = entries.sortedBy { it.start }
        return sorted
    }
}