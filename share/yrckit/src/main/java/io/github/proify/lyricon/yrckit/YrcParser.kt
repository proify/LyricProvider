/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.yrckit

import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import java.util.regex.Pattern

object YrcParser {
    private val YRC_LINE_HEADER_REGEX = Pattern.compile("\\[(\\d+),(\\d+)]")
    private val YRC_SYLLABLE_REGEX = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^(]*)")

    fun parse(raw: String?): List<LyricLine> {
        val entries = mutableListOf<LyricLine>()
        if (raw.isNullOrBlank()) return entries

        raw.lineSequence().forEach { line ->
            val trimLine = line.trim()
            if (trimLine.isBlank() || trimLine.startsWith("{")) return@forEach

            val headerMatcher = YRC_LINE_HEADER_REGEX.matcher(trimLine)
            if (headerMatcher.find()) {
                val lineStart = headerMatcher.group(1)?.toLongOrNull() ?: 0L
                val lineDuration = headerMatcher.group(2)?.toLongOrNull() ?: 0L
                val lineEnd = lineStart + lineDuration

                val words = mutableListOf<LyricWord>()
                val contentPart = trimLine.substring(headerMatcher.end())
                val wordMatcher = YRC_SYLLABLE_REGEX.matcher(contentPart)

                while (wordMatcher.find()) {
                    val start = wordMatcher.group(1)?.toLongOrNull() ?: 0L
                    val duration = wordMatcher.group(2)?.toLongOrNull() ?: 0L
                    val text = wordMatcher.group(3) ?: ""

                    if (text.isEmpty()) continue

                    words.add(
                        LyricWord(
                            begin = start,
                            end = start + duration,
                            duration = duration,
                            text = text
                        )
                    )
                }

                val sorted = words.sortedBy { it.begin }
                entries.add(
                    LyricLine(
                        begin = lineStart,
                        end = lineEnd,
                        duration = lineDuration,
                        text = sorted.joinToString("") { it.text.orEmpty() },
                        words = sorted
                    )
                )
            }
        }

        val sorted = entries.sortedBy { it.begin }
        return sorted
    }
}