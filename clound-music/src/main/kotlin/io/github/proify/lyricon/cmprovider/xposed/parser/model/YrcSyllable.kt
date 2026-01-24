/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed.parser.model

data class YrcSyllable(
    override val start: Long,
    override val end: Long,
    override val duration: Long,
    val text: String
) : TimeRange