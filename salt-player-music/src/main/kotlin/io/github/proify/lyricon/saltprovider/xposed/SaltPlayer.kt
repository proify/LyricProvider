/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.saltprovider.xposed

import android.app.Notification
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object SaltPlayer : YukiBaseHooker() {

    /** 魅族 Ticker 标志位 */
    private const val FLAG_TICKER = 0x1000000 or 0x2000000

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lyricFlow = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    
    // 播放状态流，使用 StateFlow 管理播放状态
    private val playbackStateFlow = MutableStateFlow(false)
    
    // 当前歌词状态流
    private val currentLyricFlow = MutableStateFlow<String?>(null)

    private val provider: LyriconProvider by lazy {
        LyriconFactory.createProvider(
            appContext!!,
            Constants.PROVIDER_PACKAGE_NAME,
            appContext!!.packageName,
            ProviderLogo.fromBase64(Constants.ICON)
        ).apply(LyriconProvider::register)
    }

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                observeLyrics()
                hookMedia()
                hookNotify()
            }
        }
    }

    /**
     * 观察歌词流和播放状态流，协调歌词的显示和隐藏
     */
    private fun observeLyrics() = scope.launch {
        // 观察歌词接收
        launch {
            lyricFlow.collectLatest { text ->
                handleLyricReceived(text)
            }
        }
        
        // 观察播放状态变化
        launch {
            playbackStateFlow.collectLatest { isPlaying ->
                handlePlaybackStateChanged(isPlaying)
            }
        }
    }

    /**
     * 处理接收到的新歌词
     * @param text 歌词文本，null 表示清除
     */
    private suspend fun handleLyricReceived(text: String?) {
        // 更新存储的歌词状态
        currentLyricFlow.value = text
        
        // 如果当前正在播放，发送歌词
        if (playbackStateFlow.value && text != null) {
            sendLyricToHost(text)
        }
        // 如果是清除信号或暂停状态，不发送
    }

    /**
     * 处理播放状态变化
     * @param isPlaying 是否正在播放
     */
    private suspend fun handlePlaybackStateChanged(isPlaying: Boolean) {
        if (isPlaying) {
            // 切换到播放状态，如果有存储的歌词则发送
            val currentLyric = currentLyricFlow.value
            if (currentLyric != null) {
                sendLyricToHost(currentLyric)
            }
        } else {
            // 切换到暂停/停止状态，清除歌词
            sendLyricToHost(null)
        }
    }

    /**
     * 发送歌词到宿主应用
     * @param text 歌词文本，null 表示清除
     */
    private fun sendLyricToHost(text: String?) {
        try {
            provider.player.sendText(text)
        } catch (e: Exception) {
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
        if (playbackStateFlow.value == state) return
        playbackStateFlow.value = state
        provider.player.setPlaybackState(state)
    }

    private fun hookNotify() {
        "android.app.NotificationManager".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "notify"
                    parameters(String::class.java, Int::class.java, Notification::class.java)
                }.hook {
                    after {
                        val notify = args[2] as Notification
                        if ((notify.flags and FLAG_TICKER) != 0) {
                            val ticker = notify.tickerText?.toString()
                            lyricFlow.tryEmit(ticker?.trim())
                        }
                    }
                }
            }
    }
}