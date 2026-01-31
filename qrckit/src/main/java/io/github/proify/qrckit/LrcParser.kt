/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit

import io.github.proify.qrckit.model.LrcData
import io.github.proify.qrckit.model.LrcLine

/**
 * LRC 歌词解析器
 * 支持标准及扩展 LRC 格式，包括多时间标签、毫秒/百分秒处理。
 */
object LrcParser {

    // 支持多种时间格式：[00:00.00] (百分秒), [00:00:00] (冒号分隔), [00:00.000] (毫秒)
    private val TIME_TAG_REGEX = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d+))?]""")

    // 元数据正则：匹配 [ti:标题], [ar:歌手] 等
    private val META_TAG_REGEX = Regex("""\[(\w+)\s*:\s*([^]]*)]""")

    /**
     * 解析 LRC 原始字符串
     * @param raw 歌词文本内容
     * @return 包含元数据和行数据的 [LrcData]
     */
    fun parseLrc(raw: String?): LrcData {
        if (raw.isNullOrBlank()) return LrcData(emptyMap(), emptyList())

        val tempEntries = mutableListOf<LrcLine>()
        val metaData = mutableMapOf<String, String>()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            // 过滤无效行：空行或非标签起始行
            if (trimmed.isBlank() || !trimmed.startsWith("[")) return@forEach

            val timeMatches = TIME_TAG_REGEX.findAll(trimmed).toList()

            if (timeMatches.isNotEmpty()) {
                // 处理“一文多时”情况，例如：[00:12.00][00:45.00]相同的歌词
                // 内容定义为最后一个时间标签之后的所有文本
                val lastMatch = timeMatches.last()
                val content = trimmed.substring(lastMatch.range.last + 1).trim()

                timeMatches.forEach { match ->
                    val ms = parseTimeToMs(
                        min = match.groupValues[1],
                        sec = match.groupValues[2],
                        frac = match.groupValues[3]
                    )
                    tempEntries.add(LrcLine(start = ms, text = content))
                }
            } else {
                // 尝试匹配元数据 (如 [ar:歌手])，仅当没有时间标签时执行
                META_TAG_REGEX.matchEntire(trimmed)?.let { match ->
                    metaData[match.groupValues[1]] = match.groupValues[2].trim()
                }
            }
        }

        if (tempEntries.isEmpty()) return LrcData(metaData, emptyList())

        // 1. 必须排序：处理多标签导致的时间乱序
        val sortedInitial = tempEntries.sortedBy { it.start }

        // 2. 补全每一行的持续时间 (Duration) 和结束时间 (End)
        val resultLines = mutableListOf<LrcLine>()
        for (i in sortedInitial.indices) {
            val current = sortedInitial[i]
            // 下一行的开始即为本行的结束
            val nextStart = if (i + 1 < sortedInitial.size) sortedInitial[i + 1].start else null

            // 兜底策略：最后一行默认显示 5 秒，或者直到视频/音频结束
            val end = nextStart ?: (current.start + 5000)
            resultLines.add(
                current.copy(
                    end = end,
                    duration = end - current.start
                )
            )
        }

        return LrcData(metaData, resultLines)
    }

    /**
     * 将时间标签转换为毫秒值
     * 关键逻辑：正确处理分数位 (frac) 的权重。
     * .5 -> 500ms
     * .05 -> 50ms
     * .005 -> 5ms
     */
    private fun parseTimeToMs(min: String, sec: String, frac: String?): Long {
        val m = min.toLongOrNull() ?: 0L
        val s = sec.toLongOrNull() ?: 0L

        val ms = when {
            frac.isNullOrEmpty() -> 0L
            // 只有一位数，认为是十分之一秒 (1 = 100ms)
            frac.length == 1 -> frac.toLong() * 100
            // 两位数，认为是百分之一秒 (标准 LRC 格式, 01 = 10ms)
            frac.length == 2 -> frac.toLong() * 10
            // 三位数，直接是毫秒
            frac.length == 3 -> frac.toLong()
            // 超过三位截取前三位
            else -> frac.substring(0, 3).toLong()
        }

        return m * 60000 + s * 1000 + ms
    }
}