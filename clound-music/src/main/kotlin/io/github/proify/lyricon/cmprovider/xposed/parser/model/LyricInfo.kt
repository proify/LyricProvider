/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed.parser.model

data class LyricInfo(
    var musicId: Long = 0,
    var pureMusic: Boolean = false,
    var lyrics: List<LyricLine> = emptyList(),
)