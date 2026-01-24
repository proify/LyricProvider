/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import io.github.proify.lyricon.cmprovider.xposed.parser.model.LyricResponse
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.Serializable

@Serializable
data class DiskSong(
    var song: Song? = null,
    var response: LyricResponse? = null
)