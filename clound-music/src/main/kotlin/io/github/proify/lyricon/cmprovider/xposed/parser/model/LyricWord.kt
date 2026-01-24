/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed.parser.model

data class LyricWord(
    override var start: Long = 0,
    override var end: Long = 0,
    override var duration: Long = 0,
    var text: String? = null
) : TimeRange