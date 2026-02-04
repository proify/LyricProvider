/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.common.rom

import android.app.Notification

//@Keep
//请手动在文件里配置r8混淆规则
open class MeiZuNotification : Notification() {
    companion object {
        const val FLAG_ALWAYS_SHOW_TICKER_HOOK = 0x01000000
        const val FLAG_ONLY_UPDATE_TICKER_HOOK = 0x02000000
        const val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
        const val FLAG_ONLY_UPDATE_TICKER = 0x02000000
    }
}