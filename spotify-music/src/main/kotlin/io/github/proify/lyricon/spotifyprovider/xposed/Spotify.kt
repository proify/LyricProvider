/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.common.extensions.toPairMap
import io.github.proify.lyricon.spotifyprovider.xposed.api.NoFoundLyricException
import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi
import io.github.proify.lyricon.spotifyprovider.xposed.api.response.LyricResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method

object Spotify : YukiBaseHooker(), DownloadCallback {

    private const val TAG = "SpotifyProvider"
    private var isPlaying = false
    private var lyriconProvider: LyriconProvider? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pauseRunnable = Runnable { applyPlaybackUpdate(false) }
    private var trackId: String? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var playerState: Any? = null
    private var positionMethod: Method? = null
    private var positionResult: YukiMemberHookCreator.MemberHookCreator.Result? = null
    private var positionJob: Job? = null

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "正在注入进程: $processName")

        onAppLifecycle {
            onCreate {
                initProvider()
            }
        }
        hookMediaSession()
        hookOkHttp()
        hookPlayerState()
    }

    private fun startPositionSync() {
        if (positionJob != null) return
        positionJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                val position = readPosition()
                // YLog.debug(tag = TAG, msg = "Position: $position")
                lyriconProvider?.player?.setPosition(position)
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun stopPositionSync() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun readPosition(): Long {
        return try {
            val any = positionMethod?.invoke(playerState, System.currentTimeMillis())
            val str = any.toString()
            val start = str.indexOf('(')
            val end = str.indexOf(')')
            if (start != -1 && end != -1) {
                val value = str.substring(start + 1, end)
                return if (value.isNotBlank()) value.toLong() else 0
            } else {
                0
            }
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "Failed to read position", e = e)
            0
        }
    }

    private fun hookPlayerState() {
        positionResult = "com.spotify.player.model.PlayerState".toClass()
            .resolve()
            .firstMethod {
                name = "position"
                parameters(Long::class)
            }.hook {
                after {
                    if (playerState != instance) playerState = instance
                    if (positionMethod == null) positionMethod = method
                }
            }
    }

    private fun hookOkHttp() {
        "okhttp3.Headers".toClass()
            .resolve()
            .firstConstructor()
            .hook {
                after {
                    val arg = args[0] as? Array<*> ?: return@after
                    val map = arg.toPairMap()

                    map.forEach { (key, value) ->
                        when (key) {
                            "Authorization" -> SpotifyApi.headers["authorization"] = value
                            "client-token" -> SpotifyApi.headers["client-token"] = value
                            "user-agent" -> SpotifyApi.headers["user-agent"] = value
                            "spotify-app-version" -> SpotifyApi.headers["spotify-app-version"] =
                                value

                            "x-client-id" -> SpotifyApi.headers["x-client-id"] = value
                        }
                    }
                }
            }
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.APP_PACKAGE_NAME,
            playerPackageName = Constants.MUSIC_PACKAGE_NAME,
            logo = ProviderLogo.fromSvg(Constants.ICON)
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
                    val data = MediaMetadataCache.save(metadata) ?: return@after
                    val (id, title, artist) = data

                    if (id.isBlank()) {
                        YLog.info(
                            tag = TAG,
                            msg = "Invalid track id: $id, name: $title, artist: $artist"
                        )
                        return@after
                    }
                    if (id == this@Spotify.trackId) return@after
                    this@Spotify.trackId = id

                    setSong(Song(id, title, artist))
                    onTrackIdChanged(id)
                }
            }
        }
    }

    private fun onTrackIdChanged(trackId: String) {
        Downloader.download(trackId, this)
    }

    private fun dispatchPlaybackState(state: Int) {
        mainHandler.removeCallbacks(pauseRunnable)

        when (state) {
            PlaybackState.STATE_PLAYING -> applyPlaybackUpdate(true)
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> mainHandler.postDelayed(
                pauseRunnable,
                0
            )
        }
    }

    private fun applyPlaybackUpdate(playing: Boolean) {
        if (this.isPlaying == playing) return
        this.isPlaying = playing
        YLog.debug(tag = TAG, msg = "Playback state changed: $playing")

        lyriconProvider?.player?.setPlaybackState(playing)

        if (playing) {
            startPositionSync()
        } else {
            stopPositionSync()
        }
    }

    override fun onDownloadFinished(id: String, response: LyricResponse) {
        val song = response.toSong(id)
        setSong(song)
    }

    private fun setSong(song: Song) {
        lyriconProvider?.player?.setSong(song)
    }

    override fun onDownloadFailed(id: String, e: Exception) {
        if (e is NoFoundLyricException) {
            YLog.debug(tag = TAG, msg = "No lyric found for $id")
            return
        }
        YLog.error(tag = TAG, msg = "Failed to fetch lyric for $id", e = e)
    }
}