/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit.model

import io.github.proify.qrckit.LrcParser
import io.github.proify.qrckit.QrcParser
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class LyricData(
    val lyricsRaw: String? = null,
    val translationRaw: String? = null,
    val romaRaw: String? = null
) {
    val qrcData: List<QrcData> by lazy { QrcParser.parseXML(lyricsRaw) }
    val lrcTranslationData: LrcData by lazy { LrcParser.parseLrc(translationRaw) }
    val lrcRomaData: LrcData by lazy { LrcParser.parseLrc(romaRaw) }

    val richLyricLines: List<RichLyricLine> by lazy {
        qrcData.firstOrNull()?.lines?.map { line ->
            val matchedTrans =
                lrcTranslationData.lines.firstOrNull { abs(it.start - line.start) < 100 }
            val matchedRoma = lrcRomaData.lines.firstOrNull { abs(it.start - line.start) < 100 }

            RichLyricLine(
                start = line.start,
                end = line.end,
                duration = line.duration,
                text = line.text,
                translation = matchedTrans?.text,
                roma = matchedRoma?.text,
                words = line.words
            )
        } ?: emptyList()
    }
}