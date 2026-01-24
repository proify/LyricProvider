/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import io.github.proify.lyricon.cmprovider.xposed.parser.model.LyricResponse
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

typealias RichLyricWord = io.github.proify.lyricon.lyric.model.LyricWord

fun LyricResponse.toSong(): Song {
    val info = toLyricInfo()

    val musicIdStr = info.musicId.toString()
    val metadata = MediaMetadataCache.getMetadataById(musicIdStr)

    return Song().apply {
        id = musicIdStr

        metadata?.let {
            name = it.title
            artist = it.artist
            duration = it.duration
        }
        val lyrics = info.lyrics.map { line ->
            val rich = RichLyricLine(
                begin = line.start,
                end = line.end,
                duration = line.duration,
                text = line.text,
                words = line.words.map {
                    RichLyricWord(
                        begin = it.start,
                        end = it.end,
                        duration = it.duration,
                        text = it.text
                    )
                },
                translation = line.translation
            )
            rich
        }

        this.lyrics = lyrics
    }
}