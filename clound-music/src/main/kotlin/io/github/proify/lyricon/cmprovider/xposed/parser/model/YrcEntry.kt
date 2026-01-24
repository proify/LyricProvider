/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed.parser.model

data class YrcEntry(
    override val start: Long,
    override val end: Long,
    override val duration: Long,
    val syllables: List<YrcSyllable>
) : TimeRange