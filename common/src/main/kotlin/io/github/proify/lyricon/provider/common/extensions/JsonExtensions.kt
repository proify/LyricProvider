/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.common.extensions

import kotlinx.serialization.json.Json

val json: Json = Json {
    coerceInputValues = true     // 尝试转换类型
    ignoreUnknownKeys = true     // 忽略未知字段
    isLenient = true             // 宽松的 JSON 语法
    explicitNulls = false        // 不序列化 null
    //encodeDefaults = false       // 不序列化默认值
}