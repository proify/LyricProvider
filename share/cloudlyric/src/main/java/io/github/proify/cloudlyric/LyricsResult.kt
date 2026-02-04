/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric

import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.serialization.Serializable

/**
 * 歌词数据模型
 *
 * @param trackName 曲名
 * @param artistName 歌手名
 * @param albumName 专辑名
 * @param rich 富歌词
 * @param instrumental 是否纯音乐
 */
@Serializable
data class LyricsResult(
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val rich: List<RichLyricLine> = emptyList(),
    val instrumental: Boolean = false,
)