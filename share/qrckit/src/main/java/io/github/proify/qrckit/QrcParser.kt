/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit

import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.qrckit.model.QrcData

object QrcParser {

    // 匹配行级时间标签，格式如：[开始时间,持续时间]，例如 [1000,2500]
    private val linePattern = Regex("""\[(\d+)\s*,\s*(\d+)]""")

    // 匹配逐字时间标签，格式如：文本(开始时间,持续时间)
    // (.*?)：非贪婪匹配文本，支持文本中包含括号的情况
    // \((\d+)\s*,\s*(\d+)\)：匹配紧跟在文本后的绝对时间戳
    private val wordPattern = Regex("""(.*?)\((\d+)\s*,\s*(\d+)\)""")

    // 匹配元数据标签，格式如：[ti:歌名] [ar:歌手]
    private val metaPattern = Regex("""\[(\w+)\s*:\s*([^]]*)]""")

    // 提取 XML 包装中的 LyricContent 属性值
    private val lyricContentRegex = Regex("""LyricContent\s*=\s*"([^"]+)"""")

    /**
     * 解析包含 QRC 数据的 XML 字符串
     * * @param xml 原始 XML 字符串，可能包含多个歌词版本
     * @return 解析后的 [QrcData] 列表
     */
    fun parseXML(xml: String?): List<QrcData> {
        if (xml.isNullOrBlank()) return emptyList()

        return lyricContentRegex.findAll(xml).mapNotNull { match ->
            // 提取被 XML 转义过的原始字符串
            val rawContent = match.groupValues[1]

            // 还原 XML 实体字符（如 &apos; -> '），否则正则匹配会因非法字符失效
            val cleanContent = unescapeXml(rawContent)

            if (cleanContent.isBlank()) null else {
                val (meta, lines) = parseLyric(cleanContent)
                QrcData(meta, lines)
            }
        }.toList()
    }

    /**
     * 解析纯 QRC 文本内容
     * * @param content 已经过反转义处理的 QRC 文本
     * @return 包含元数据 Map 和行列表 [LyricLine] 的 Pair
     */
    fun parseLyric(content: String): Pair<Map<String, String>, List<LyricLine>> {
        val metaData = mutableMapOf<String, String>()
        val lines = mutableListOf<LyricLine>()

        // 1. 解析元数据 (ID3 tags)
        metaPattern.findAll(content).forEach {
            metaData[it.groupValues[1]] = it.groupValues[2].trim()
        }

        // 2. 解析行内容：通过寻找 [start, dur] 标签来划分逻辑行
        val lineMatches = linePattern.findAll(content).toList()

        for (i in lineMatches.indices) {
            val currentMatch = lineMatches[i]
            val lineStart = currentMatch.groupValues[1].toLongOrNull() ?: 0L
            val lineDur = currentMatch.groupValues[2].toLongOrNull() ?: 0L

            // 确定当前行标签后到下一行标签前的文本范围
            val bodyStart = currentMatch.range.last + 1
            val bodyEnd = if (i + 1 < lineMatches.size) {
                lineMatches[i + 1].range.first
            } else {
                content.length
            }

            if (bodyStart < bodyEnd) {
                val lineBody = content.substring(bodyStart, bodyEnd)
                // 排除只有空白符的无效行
                if (lineBody.isNotBlank()) {
                    lines.add(parseLineBody(lineStart, lineDur, lineBody))
                }
            }
        }

        // QRC 可能存在无序排列，按开始时间统一排序以保证渲染正常
        return metaData to lines.sortedBy { it.begin }
    }

    /**
     * 解析行内详情：将行文本拆解为逐字时间对象 [LyricWord]
     */
    private fun parseLineBody(lineStart: Long, lineDur: Long, rawBody: String): LyricLine {
        // 清理换行符，防止解析逐字时出现干扰
        val trimmedBody = rawBody.trim('\n', '\r')

        val words = wordPattern.findAll(trimmedBody).mapNotNull { match ->
            val text = match.groupValues[1]
            val wStart = match.groupValues[2].toLongOrNull() ?: 0L
            val wDur = match.groupValues[3].toLongOrNull() ?: 0L

            // 过滤无效的负数时长数据
            if (wDur < 0) null else {
                LyricWord(
                    begin = wStart,     // QRC 字时间通常为绝对时间
                    end = wStart + wDur,
                    duration = wDur,
                    text = text
                )
            }
        }.toList()

        // 确定展示文本：如果无法解析出逐字信息，则剥离标签作为纯文本显示
        val finalText = if (words.isEmpty()) {
            trimmedBody.replace(Regex("""\(\d+,\d+\)"""), "")
        } else {
            words.joinToString("") { it.text ?: "" }
        }

        return LyricLine(
            begin = lineStart,
            duration = lineDur,
            end = lineStart + lineDur,
            text = finalText,
            words = words
        )
    }

    /**
     * 基础 XML 转义字符还原
     * 处理 QRC 封装在 XML 属性中时产生的常用转义实体
     */
    private fun unescapeXml(source: String): String {
        return source
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            // 处理常见的数值转义字符（如换行符）
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")
    }
}