/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricLine(
    override val start: Long = 0,
    override val end: Long = 0,
    override val duration: Long = 0,
    val text: String? = null,
    val words: List<LyricWord> = emptyList()
) : TimeRange