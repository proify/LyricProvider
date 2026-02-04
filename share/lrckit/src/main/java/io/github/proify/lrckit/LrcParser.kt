/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lrckit

import io.github.proify.lyricon.lyric.model.LyricLine

/**
 * LRC 歌词解析器
 *
 * ## 支持的格式
 * 1. **标准格式**: `[00:12.34]` (百分秒)
 * 2. **高精度格式**: `[00:12.345]` (毫秒)
 * 3. **非标冒号格式**: `[00:12:34]` 或 `[00:12:345]` (fix: 兼容部分平台导出的冒号分隔符)
 * 4. **简易格式**: `[00:12]` (无小数部分)
 * 5. **多时间标签**: `[00:12.00][00:45.00]文本内容` (同一行歌词对应多个时间点)
 * 6. **分钟溢出**: `[120:00.00]` (支持超过 99 分钟的长音频)
 */
object LrcParser {

    /**
     * 时间标签正则表达式
     *
     * 匹配逻辑说明：
     * - `(\d+)`: 分钟，支持 1 位或多位数字。
     * - `(\d{1,2})`: 秒数，通常为 2 位。
     * - `([.:](\d{1,3}))?`: 小数部分。
     * - `[.:]`: 兼容点号 `.` 或冒号 `:` 分隔符 (修复兼容性问题 30373825)。
     * - `(\d{1,3})`: 匹配 1-3 位数字（毫秒、百分秒或十分之一秒）。
     */
    private val TIME_TAG_REGEX = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * 元数据标签正则表达式 (如 [ti:Song Title])
     */
    private val META_TAG_REGEX = Regex("""\[(\w+)\s*:\s*([^]]*)]""")

    /**
     * 解析 LRC 原始字符串
     *
     * @param raw 原始歌词文本
     * @param duration 音频总时长，用于计算最后一行歌词的结束时间
     * @return 包含解析后的元数据和歌词行的 [LrcDocument]
     */
    fun parseLrc(raw: String?, duration: Long = 0): LrcDocument {
        if (raw.isNullOrBlank()) return LrcDocument(emptyMap(), emptyList())

        val tempEntries = mutableListOf<LyricLine>()
        val metaData = mutableMapOf<String, String>()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || !trimmed.startsWith("[")) return@forEach

            val timeMatches = TIME_TAG_REGEX.findAll(trimmed).toList()

            if (timeMatches.isNotEmpty()) {
                // 内容定义为最后一个时间标签之后的所有文本
                val lastMatch = timeMatches.last()
                val content = trimmed.substring(lastMatch.range.last + 1).trim()

                timeMatches.forEach { match ->
                    val ms = parseTimeToMs(
                        min = match.groupValues[1],
                        sec = match.groupValues[2],
                        frac = match.groupValues.getOrNull(3) // 对应 (\d{1,3})
                    )
                    tempEntries.add(LyricLine(begin = ms, text = content))
                }
            } else {
                // 尝试匹配元数据
                META_TAG_REGEX.matchEntire(trimmed)?.let { match ->
                    metaData[match.groupValues[1]] = match.groupValues[2].trim()
                }
            }
        }

        if (tempEntries.isEmpty()) return LrcDocument(metaData, emptyList())

        // 按开始时间排序并计算每行持续时间
        val sortedInitial = tempEntries.sortedBy { it.begin }
        val resultLines = sortedInitial.mapIndexed { index, current ->
            val nextStart =
                if (index + 1 < sortedInitial.size) sortedInitial[index + 1].begin else null
            val end =
                nextStart ?: if (duration > 0) duration else current.begin + 5000L
            current.copy(
                end = end,
                duration = end - current.begin
            )
        }

        return LrcDocument(metaData, resultLines)
    }

    /**
     * 将时间标签的分量转换为毫秒
     *
     * 精度处理逻辑：
     * - 1位 (如 .5): 5 * 100 = 500ms
     * - 2位 (如 .05): 05 * 10 = 50ms (标准 LRC 百分秒)
     * - 3位 (如 .005): 005 = 5ms (高精度毫秒)
     *
     * @param min 分钟部分
     * @param sec 秒部分
     * @param frac 小数部分（毫秒/百分秒），可能为 null
     */
    private fun parseTimeToMs(min: String, sec: String, frac: String?): Long {
        val m = min.toLongOrNull() ?: 0L
        val s = sec.toLongOrNull() ?: 0L

        val ms = when (frac?.length) {
            null, 0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            3 -> frac.toLong()
            else -> frac.substring(0, 3).toLongOrNull() ?: 0L
        }

        return m * 60000 + s * 1000 + ms
    }
}