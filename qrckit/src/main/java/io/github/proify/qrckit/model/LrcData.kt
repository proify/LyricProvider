/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit.model

import kotlinx.serialization.Serializable

@Serializable
data class LrcData(
    val metaData: Map<String, String>,
    val lines: List<LrcLine>
)