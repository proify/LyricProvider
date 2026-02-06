/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lrckit

import io.github.proify.lyricon.lyric.model.LyricLine
import java.util.regex.Pattern

/**
 * LRC 解析器。
 * 针对网易云等平台非标格式优化，强制降级解析 [mm:ss:ms]，防止小时级偏移错误。
 */
object LrcParser {

    private val LINE_PATTERN =
        Pattern.compile("""^\[(\d{1,3})[ :.](\d{2})(?:[ :.](\d{1,3}))?](.*)""")
    private val TAG_PATTERN = Pattern.compile("""\[(\d{1,3})[ :.](\d{2})(?:[ :.](\d{1,3}))?]""")

    /**
     * 解析原始歌词。
     * @param raw 原始文本，自动过滤非 '[' 开头的干扰行（如 JSON）。
     * @param duration 音频总时长，用于修正末行结束时间。
     */
    fun parse(raw: String?, duration: Long = 0): LrcDocument {
        if (raw.isNullOrBlank()) return LrcDocument(emptyMap(), emptyList())

        val entries = mutableListOf<LyricLine>()
        val meta = mutableMapOf<String, String>()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("[")) return@forEach

            val matcher = LINE_PATTERN.matcher(trimmed)
            if (matcher.matches()) {
                // 提取最后一个 ']' 之后的内容作为正文
                val content = trimmed.substring(trimmed.lastIndexOf(']') + 1).trim()
                val tagMatcher = TAG_PATTERN.matcher(trimmed)
                while (tagMatcher.find()) {
                    entries.add(
                        LyricLine(
                            begin = toMs(
                                tagMatcher.group(1),
                                tagMatcher.group(2),
                                tagMatcher.group(3)
                            ),
                            text = content
                        )
                    )
                }
            } else {
                parseMeta(trimmed, meta)
            }
        }
        return finalize(entries, meta, duration)
    }

    /**
     * 时间转毫秒。强制识别为 [分:秒:毫秒]，自适应毫秒位数。
     */
    private fun toMs(mStr: String, sStr: String, fStr: String?): Long {
        val m = mStr.toLongOrNull() ?: 0L
        val s = sStr.toLongOrNull() ?: 0L
        val ms = when (fStr?.length) {
            1 -> fStr.toLong() * 100 // [ss:5] -> 500ms
            2 -> fStr.toLong() * 10  // [ss:50] -> 500ms
            3 -> fStr.toLong()       // [ss:500] -> 500ms
            else -> 0L
        }
        return m * 60000 + s * 1000 + ms
    }

    private fun parseMeta(line: String, meta: MutableMap<String, String>) {
        val colon = line.indexOf(":")
        if (colon > 1 && line.endsWith("]")) {
            val key = line.substring(1, colon).trim()
            val value = line.substring(colon + 1, line.length - 1).trim()
            meta[key] = value
        }
    }

    private fun finalize(list: List<LyricLine>, meta: Map<String, String>, dur: Long): LrcDocument {
        val sorted = list.sortedBy { it.begin }
        val lines = sorted.mapIndexed { i, cur ->
            val next = sorted.getOrNull(i + 1)?.begin
            val end = next ?: if (dur > cur.begin) dur else cur.begin + 5000L
            cur.copy(end = end, duration = end - cur.begin)
        }
        return LrcDocument(meta, lines)
    }
}