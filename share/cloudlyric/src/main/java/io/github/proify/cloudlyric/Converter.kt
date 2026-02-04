/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric

import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.RichLyricLine
import java.util.TreeMap

/**
 * 将普通歌词行列表转换为包含翻译和罗马音的富文本歌词行。
 * * 优化点：
 * 1. 预解析辅助歌词，避免循环内重复解析。
 * 2. 引入 [threshold] 允许时间戳存在微小误差。
 * 3. 使用 [TreeMap] 进行高效的时间范围检索。
 *
 * @param transLrc 翻译歌词文本
 * @param romaLrc 罗马音歌词文本
 * @param threshold 允许的时间戳匹配误差（毫秒）
 * @return 组装后的 [RichLyricLine] 列表
 */
fun List<LyricLine>.toRichLines(
    transLrc: String? = null,
    romaLrc: String? = null,
    threshold: Long = 100L
): List<RichLyricLine> {

    // 预将辅助歌词解析为 TreeMap，Key 为开始时间戳
    val transMap = transLrc?.let { LrcParser.parseLrc(it).lines.toTreeMap() }
    val romaMap = romaLrc?.let { LrcParser.parseLrc(it).lines.toTreeMap() }

    return this.map { line ->
        RichLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            text = line.text,
            translation = transMap?.findNearbyText(line.begin, threshold),
            roma = romaMap?.findNearbyText(line.begin, threshold)
        )
    }
}

/**
 * 将歌词行列表转换为 TreeMap 以支持范围查找。
 */
private fun List<LyricLine>.toTreeMap(): TreeMap<Long, String> {
    val map = TreeMap<Long, String>()
    this.forEach { map[it.begin] = it.text.orEmpty() }
    return map
}

/**
 * 在 TreeMap 中寻找最接近 [targetTime] 且在 [threshold] 范围内的文本。
 */
private fun TreeMap<Long, String>.findNearbyText(targetTime: Long, threshold: Long): String? {
    // 查找时间戳小于等于 targetTime 的最大元素
    val floor = this.floorEntry(targetTime)
    if (floor != null && targetTime - floor.key <= threshold) {
        return floor.value
    }

    // 查找时间戳大于等于 targetTime 的最小元素
    val ceiling = this.ceilingEntry(targetTime)
    if (ceiling != null && ceiling.key - targetTime <= threshold) {
        return ceiling.value
    }

    return null
}