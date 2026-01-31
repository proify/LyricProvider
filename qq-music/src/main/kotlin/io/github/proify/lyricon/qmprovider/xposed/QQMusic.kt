/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.qrckit.LyricResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

typealias LyriconRichLyricLine = io.github.proify.lyricon.lyric.model.RichLyricLine
typealias LyriconLyricWord = io.github.proify.lyricon.lyric.model.LyricWord

object QQMusic : YukiBaseHooker() {
    private const val TAG = "Lyricon_QQMusic"
    private const val PKG_MAIN = "com.tencent.qqmusic"
    private const val PKG_PLAYER_SERVICE = "com.tencent.qqmusic:QQPlayerService"

    private const val ACTION_LYRIC_SETTINGS_CHANGED =
        "io.github.proify.lyricon.ACTION_SETTINGS_CHANGED"
    private const val PREF_NAME_QQMUSIC = "qqmusicplayer"
    private const val KEY_DISPLAY_TRANS = "showtranslyric"
    private const val KEY_DISPLAY_ROMA = "showromalyric"

    private val mainProcessHook by lazy { MainProcessHook() }
    private val playerProcessHook by lazy { PlayerProcessHook() }

    override fun onHook() {
        val loader = appClassLoader ?: return
        when (processName) {
            PKG_MAIN -> mainProcessHook.hook(loader)
            PKG_PLAYER_SERVICE -> playerProcessHook.hook(loader)
        }
    }

    /**
     * 处理主进程逻辑：监听 QQ 音乐内部设置变更并广播
     */
    private class MainProcessHook {
        fun hook(loader: ClassLoader) {
            YLog.debug("Hooking Main Process: SharedPreferences interceptor")

            $$"android.app.SharedPreferencesImpl$EditorImpl".toClass(loader)
                .resolve()
                .firstMethod {
                    name = "putBoolean"
                    parameters(String::class.java, Boolean::class.java)
                }.hook {
                    after {
                        val key = args[0] as String
                        val value = args[1] as Boolean

                        if (key == KEY_DISPLAY_TRANS || key == KEY_DISPLAY_ROMA) {
                            val intent = Intent(ACTION_LYRIC_SETTINGS_CHANGED).apply {
                                putExtra("setting_key", key)
                                putExtra("setting_value", value)
                                setPackage(appContext?.packageName)
                            }
                            appContext?.sendBroadcast(intent)
                            Log.d(TAG, "Settings changed in main process: $key -> $value")
                        }
                    }
                }
        }
    }

    /**
     * 处理播放服务进程逻辑：核心 Hook 与 Lyricon 交互
     */
    private class PlayerProcessHook : DownloadCallback {
        private val hookScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var positionUpdateJob: Job? = null
        private var lyriconProvider: LyriconProvider? = null

        private var isMediaPlaying = false
        private var currentMediaId: String? = null

        fun hook(loader: ClassLoader) {
            YLog.debug("Hooking Player Process: MediaSession & Lyricon Provider")

            onAppLifecycle {
                onCreate {
                    DiskSongCache.initialize(this)
                    setupLyriconProvider(this)
                    registerSettingsReceiver(this)
                }
            }

            "android.media.session.MediaSession".toClass(loader)
                .resolve().apply {
                    firstMethod {
                        name = "setPlaybackState"
                        parameters(PlaybackState::class.java)
                    }.hook {
                        after {
                            val state = (args[0] as? PlaybackState)?.state ?: return@after
                            when (state) {
                                PlaybackState.STATE_PLAYING -> notifyPlaybackStarted()
                                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> notifyPlaybackStopped()
                            }
                        }
                    }

                    // 监听歌曲切歌
                    firstMethod {
                        name = "setMetadata"
                        parameters(MediaMetadata::class.java)
                    }.hook {
                        after {
                            val metadata = args[0] as? MediaMetadata ?: return@after
                            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                                ?: return@after

                            if (mediaId.isBlank() || mediaId == currentMediaId) return@after

                            currentMediaId = mediaId
                            MediaMetadataCache.save(metadata)
                            refreshActiveSong()
                        }
                    }
                }
        }

        private fun registerSettingsReceiver(application: Application) {
            val filter = IntentFilter(ACTION_LYRIC_SETTINGS_CHANGED)
            ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val key = intent?.getStringExtra("setting_key") ?: return
                    val value = intent.getBooleanExtra("setting_value", false)

                    when (key) {
                        KEY_DISPLAY_TRANS -> lyriconProvider?.player?.setDisplayTranslation(value)
                        KEY_DISPLAY_ROMA -> lyriconProvider?.player?.setDisplayRoma(value)
                    }
                }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        private fun setupLyriconProvider(application: Application) {
            val provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.APP_PACKAGE_NAME,
                playerPackageName = PKG_MAIN,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            )

            // 初始化显示设置
            val prefs = application.getSharedPreferences(PREF_NAME_QQMUSIC, Context.MODE_PRIVATE)
            provider.player.apply {
                setDisplayTranslation(prefs.getBoolean(KEY_DISPLAY_TRANS, false))
                setDisplayRoma(prefs.getBoolean(KEY_DISPLAY_ROMA, false))
            }

            provider.register()
            this.lyriconProvider = provider
        }

        private fun notifyPlaybackStarted() {
            if (isMediaPlaying) return
            isMediaPlaying = true
            lyriconProvider?.player?.setPlaybackState(true)
            launchPositionTracker()
        }

        private fun notifyPlaybackStopped() {
            isMediaPlaying = false
            lyriconProvider?.player?.setPlaybackState(false)
            stopPositionTracker()
        }

        private fun launchPositionTracker() {
            if (positionUpdateJob != null) return
            positionUpdateJob = hookScope.launch {
                while (isActive && isMediaPlaying) {
                    lyriconProvider?.player?.setPosition(fetchCurrentTimeFromQQMusic())
                    delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
                }
            }
        }

        private fun stopPositionTracker() {
            positionUpdateJob?.cancel()
            positionUpdateJob = null
        }

        // --- 反射获取 QQ 音乐播放位置 ---

        private val playProcessMethodsInstance: Any? by lazy {
            runCatching {
                "com.tencent.qqmusic.common.ipc.PlayProcessMethods".toClass()
                    .getDeclaredMethod("get")
                    .invoke(null)
            }.getOrNull()
        }

        private val getCurrentTimeMethod by lazy {
            runCatching {
                playProcessMethodsInstance?.javaClass?.getMethod("getCurrTime")
            }.getOrNull()
        }

        private fun fetchCurrentTimeFromQQMusic(): Long {
            return try {
                getCurrentTimeMethod?.invoke(playProcessMethodsInstance) as? Long ?: 0
            } catch (e: Throwable) {
                0
            }
        }

        // --- 歌曲数据处理 ---

        private fun refreshActiveSong() {
            val mediaId = currentMediaId ?: return

            if (DiskSongCache.isCached(mediaId)) {
                updateLyriconSong(DiskSongCache.get(mediaId))
            } else {
                updateSongWithPlaceholder(mediaId)
            }

            DownloadManager.download(mediaId, this)
        }

        private fun updateSongWithPlaceholder(mediaId: String) {
            val metadata = MediaMetadataCache.get(mediaId)
            updateLyriconSong(
                Song(
                    id = mediaId,
                    name = metadata?.title,
                    artist = metadata?.artist,
                    metadata = lyricMetadataOf("placeholder" to "true")
                )
            )
        }

        private fun updateLyriconSong(song: Song?) {
            lyriconProvider?.player?.setSong(song)
        }

        override fun onDownloadFinished(response: LyricResponse) {
            val song = response.toLyriconSong()
            DiskSongCache.put(song)

            if (response.id == currentMediaId) {
                // 如果当前是占位符，则更新为完整歌词
                updateLyriconSong(song)
            }
        }

        override fun onDownloadFailed(id: String, e: Exception) {
            YLog.error("$TAG: Lyric download failed for $id", e)
        }

        private fun LyricResponse.toLyriconSong(): Song {
            val cachedMetadata = MediaMetadataCache.get(id)
            val mappedLyrics = lyricData.richLyricLines.map { line ->
                LyriconRichLyricLine(
                    begin = line.start, end = line.end, duration = line.duration,
                    text = line.text, translation = filterTranslation(line.translation),
                    words = line.words.map { word ->
                        LyriconLyricWord(word.start, word.end, word.duration, word.text)
                    }
                )
            }
            return Song(
                id = id,
                name = cachedMetadata?.title,
                artist = cachedMetadata?.artist,
                duration = cachedMetadata?.duration ?: 0,
                lyrics = mappedLyrics
            )
        }

        fun filterTranslation(translation: String?): String? =
            if (translation?.trim() == "//") return null else translation
    }
}