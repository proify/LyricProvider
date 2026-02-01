/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.common.util

import android.media.session.PlaybackState
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers

/**
 * @author Lin
 */
object Utils {
    fun openBluetoothA2dpOn(classLoader: ClassLoader?) {
        if (classLoader == null) return
        XposedHelpers.findAndHookMethod(
            "android.media.AudioManager",
            classLoader,
            "isBluetoothA2dpOn",
            XC_MethodReplacement.returnConstant(true)
        )
        XposedHelpers.findAndHookMethod(
            "android.bluetooth.BluetoothAdapter",
            classLoader,
            "isEnabled",
            XC_MethodReplacement.returnConstant(true)
        )
    }

    fun getStringForStateInt(state: Int): String {
        return when (state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
            else -> "UNKNOWN"
        }
    }
}