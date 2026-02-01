/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.kgprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.common.util.Utils

object KuGou : YukiBaseHooker() {
    private const val TAG = "KuGouMusicProvider"
    private const val TARGET_PROCESS = "com.kugou.android.support"

    private var currentPlayingState = false
    private var lyriconProvider: LyriconProvider? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val pauseRunnable = Runnable { applyPlaybackUpdate(false) }

    init {
        System.loadLibrary("dexkit")
    }

    override fun onHook() {
        Utils.openBluetoothA2dpOn(appClassLoader)

        if (processName != TARGET_PROCESS) return

        YLog.debug(tag = TAG, msg = "正在注入进程: $processName")

        onAppLifecycle {
            onCreate {
                initProvider()
            }
        }

        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.APP_PACKAGE_NAME,
            playerPackageName = Constants.MUSIC_PACKAGE_NAME,
            logo = ProviderLogo.fromBase64(Constants.ICON)
        ).apply { register() }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = (args[0] as? PlaybackState)?.state ?: return@after
                    dispatchPlaybackState(state)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    if (!title.isNullOrBlank()) {
                        lyriconProvider?.player?.sendText(title)
                    }
                }
            }
        }
    }

    private fun dispatchPlaybackState(state: Int) {
        mainHandler.removeCallbacks(pauseRunnable)

        when (state) {
            PlaybackState.STATE_PLAYING -> applyPlaybackUpdate(true)
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> mainHandler.postDelayed(
                pauseRunnable,
                150
            )
        }
    }

    private fun applyPlaybackUpdate(playing: Boolean) {
        if (this.currentPlayingState == playing) return
        this.currentPlayingState = playing

        YLog.debug(tag = TAG, msg = "Playback state changed: $playing")
        lyriconProvider?.player?.setPlaybackState(playing)
    }
}