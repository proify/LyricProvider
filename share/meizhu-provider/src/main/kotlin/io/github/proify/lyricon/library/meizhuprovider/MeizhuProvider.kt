/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.library.meizhuprovider

import android.app.Notification
import android.app.NotificationManager
import android.media.session.PlaybackState
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.common.rom.Flyme
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

open class MeizhuProvider(
    val providerPackageName: String,
    val logo: ProviderLogo = ProviderLogo.fromBase64(Constants.ICON)
) : YukiBaseHooker() {

    private companion object {
        private const val FLAG_MEIZU_TICKER = 0x1000000 or 0x2000000
        private const val TAG = "MeizhuProvider"
    }

    private var isPlaying = false

    private val provider: LyriconProvider by lazy {
        LyriconFactory.createProvider(
            appContext!!,
            providerPackageName,
            appContext!!.packageName,
            logo
        ).apply(LyriconProvider::register)
    }

    override fun onHook() {
        YLog.debug("Hooking processName: $processName")
        Flyme.mock(appClassLoader!!)
        onAppLifecycle {
            onCreate {
                hookMedia()
                hookNotify()
            }
        }
    }

    private fun hookMedia() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = (args[0] as PlaybackState).state
                        when (state) {
                            PlaybackState.STATE_PLAYING -> updatePlaybackStatus(true)
                            PlaybackState.STATE_PAUSED,
                            PlaybackState.STATE_STOPPED -> updatePlaybackStatus(false)

                            else -> Unit
                        }
                    }
                }
            }
    }

    private fun updatePlaybackStatus(state: Boolean) {
        if (isPlaying == state) return
        isPlaying = state
        provider.player.setPlaybackState(state)
    }

    private fun hookNotify() {
        NotificationManager::class.java.name.toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "notify"
                    parameters(String::class, Int::class, Notification::class)
                }.hook {
                    after {
                        val notify = args[2] as Notification
                        //Log.d(TAG, "notify: $notify")
                        if ((notify.flags and FLAG_MEIZU_TICKER) != 0) {
                            Log.d(TAG, "ticker: ${notify.tickerText}")
                            val ticker = notify.tickerText?.toString()
                            provider.player.sendText(ticker)
                        }
                    }
                }
            }
    }
}