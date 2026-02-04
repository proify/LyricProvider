/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lxprovider.xposed.variant.main

import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.normalize

object Converter {

    fun List<RichLyricLine>.toSong(id: String) = Song(id = id).apply {
        lyrics = this@toSong
        duration = this@toSong.maxOfOrNull { it.end } ?: Long.MAX_VALUE
    }

    fun toRich(
        lyric: String,
        translation: String? = null,
        roma: String? = null
    ): List<RichLyricLine> {
        val main = LrcParser.parseLrc(lyric).lines
        val transMap =
            translation?.let { LrcParser.parseLrc(it).lines.associateBy { l -> l.begin } }
        val romaMap = roma?.let { LrcParser.parseLrc(it).lines.associateBy { l -> l.begin } }

        return main
            .filter { !it.text.isNullOrBlank() }
            .map { m ->
                RichLyricLine(
                    text = m.text,
                    begin = m.begin,
                    end = m.end,
                    duration = m.duration,
                    translation = transMap?.get(m.begin)?.text.takeIf { it != "//" }, //移除QQ音乐无效翻译
                    roma = romaMap?.get(m.begin)?.text
                )
            }.normalize()
    }
}