/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit.model

import io.github.proify.lrckit.LrcDocument
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.qrckit.QrcParser
import kotlinx.serialization.Serializable
import java.util.TreeMap
import kotlin.math.abs

/**
 * 时间戳匹配容差（毫秒）。
 */
private const val MATCH_TOLERANCE = 100L

/**
 * 封装原始歌词数据并提供解析能力的业务模型。
 *
 * @property lyricsRaw 原始逐字或逐行歌词文本（支持 QRC 或 LRC 格式）。
 * @property translationRaw 原始翻译歌词文本（LRC 格式）。
 * @property romaRaw 原始罗马音歌词文本（LRC 格式）。
 */
@Serializable
data class ParsedLyric(
    val lyricsRaw: String? = null,
    val translationRaw: String? = null,
    val romaRaw: String? = null
) {
    /**
     * 解析并合并后的富文本歌词行列表。
     *
     * 该属性采用延迟加载，首次访问时会进行复杂的匹配逻辑。
     * 优化点：使用 [TreeMap] 实现 $O(\log N)$ 的范围查找，替代原本在查找失败时 $O(N)$ 的遍历。
     */
    val richLyricLines: List<RichLyricLine> by lazy {
        val raw = lyricsRaw?.takeIf { it.isNotBlank() } ?: return@lazy emptyList()

        // 1. 将辅助歌词转换为 TreeMap 以支持快速模糊搜索
        val transIndex = translationData.lines.toTimeIndex()
        val romaIndex = lrcRomaData.lines.toTimeIndex()

        // 2. 确定主歌词源：优先 QRC，其次 LRC
        val sourceLines = runCatching { QrcParser.parseXML(raw).firstOrNull()?.lines }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: lrcDocument.lines

        // 3. 转换并合并
        sourceLines.map { line ->
            RichLyricLine(
                begin = line.begin,
                end = line.end,
                duration = line.duration,
                text = line.text,
                translation = findBestMatch(line.begin, transIndex),
                roma = findBestMatch(line.begin, romaIndex),
                words = line.words
            )
        }
    }

    // --- 内部解析属性 ---

    private val lrcDocument: LrcDocument by lazy {
        val raw = lyricsRaw
        if (raw.isNullOrBlank()) LrcDocument() else LrcParser.parseLrc(raw)
    }

    private val translationData: LrcDocument by lazy {
        val raw = translationRaw
        if (raw.isNullOrBlank()) LrcDocument() else LrcParser.parseLrc(raw)
    }

    private val lrcRomaData: LrcDocument by lazy {
        val raw = romaRaw
        if (raw.isNullOrBlank()) LrcDocument() else LrcParser.parseLrc(raw)
    }

    /**
     * 将歌词列表转换为以开始时间为 Key 的 [TreeMap]。
     */
    private fun List<LyricLine>.toTimeIndex(): TreeMap<Long, String> {
        val map = TreeMap<Long, String>()
        for (line in this) {
            map[line.begin] = line.text.orEmpty()
        }
        return map
    }

    /**
     * 在索引中寻找最接近给定 [startTime] 且在容差范围内的文本。
     *
     * @param startTime 目标开始时间。
     * @param index 预构建的时间戳索引映射。
     * @return 匹配的文本，若无匹配则返回 null。
     */
    private fun findBestMatch(startTime: Long, index: TreeMap<Long, String>): String? {
        if (index.isEmpty()) return null

        // 直接匹配
        index[startTime]?.let { return it }

        // 模糊匹配：寻找最近的邻居
        val floor = index.floorEntry(startTime)
        val ceil = index.ceilingEntry(startTime)

        val floorDiff = floor?.let { abs(it.key - startTime) } ?: Long.MAX_VALUE
        val ceilDiff = ceil?.let { abs(it.key - startTime) } ?: Long.MAX_VALUE

        return when {
            floorDiff <= ceilDiff && floorDiff < MATCH_TOLERANCE -> floor?.value
            ceilDiff < MATCH_TOLERANCE -> ceil?.value
            else -> null
        }
    }
}