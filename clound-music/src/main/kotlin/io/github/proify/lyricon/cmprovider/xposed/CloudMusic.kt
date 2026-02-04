/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.cmprovider.xposed

import android.app.Application
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.cmprovider.xposed.Constants.ICON
import io.github.proify.lyricon.cmprovider.xposed.Constants.PROVIDER_PACKAGE_NAME
import io.github.proify.lyricon.cmprovider.xposed.PreferencesMonitor.PreferenceCallback
import io.github.proify.lyricon.cmprovider.xposed.parser.ResponseParser
import io.github.proify.lyricon.cmprovider.xposed.parser.toSong
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method

object CloudMusic : YukiBaseHooker() {
    private const val TAG: String = "CloudMusicProvider"
    private val playProgressHooker by lazy { PlayProgressHooker() }

    init {
        System.loadLibrary("dexkit")
    }

    override fun onHook() {
        when (processName) {
            "com.netease.cloudmusic",
            "com.netease.cloudmusic:play" -> {
                YLog.debug(tag = TAG, msg = "Hooking $processName")
                playProgressHooker.onHook()
            }
        }
    }

    private class PlayProgressHooker : LyricFileObserver.FileObserverCallback {
        private var provider: LyriconProvider? = null
        private var lastSong: Song? = null
        private val hotHooker = HotHooker()
        private var currentMusicId: String? = null
        private var lyricFileObserver: LyricFileObserver? = null

        private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
        private var progressJob: Job? = null

        private var isPlaying = false

        private var dexKitBridge: DexKitBridge? = null
        private var preferencesMonitor: PreferencesMonitor? = null

        fun onHook() {
            YLog.debug("Hooking, processName= $processName")

            dexKitBridge = DexKitBridge.create(appInfo.sourceDir)
            preferencesMonitor = PreferencesMonitor(dexKitBridge!!, object : PreferenceCallback {
                override fun onTranslationOptionChanged(isTranslationSelected: Boolean) {
                    provider?.player?.setDisplayTranslation(isTranslationSelected)
                }
            })
            rehook(appClassLoader!!)

            onAppLifecycle {
                onCreate {
                    setupLyricFileObserver()
                    setupProvider()
                }
            }

            "com.tencent.tinker.loader.TinkerLoader".toClass(appClassLoader)
                .resolve()
                .method { name = "tryLoad" }
                .forEach {
                    it.hook {
                        after {
                            val app = args[0] as Application
                            rehook(app.classLoader)
                        }
                    }
                }
        }

        private fun rehook(classLoader: ClassLoader) {
            preferencesMonitor?.update(classLoader)
            hotHooker.rehook(classLoader)
        }

        fun setupLyricFileObserver() {
            lyricFileObserver?.stop()
            lyricFileObserver = LyricFileObserver(appContext!!, this)
            lyricFileObserver?.start()
        }

        private fun setupProvider() {
            val application = appContext ?: return
            provider?.destroy()

            val newProvider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromSvg(ICON)
            )

            newProvider.player.apply {
                setDisplayTranslation(preferencesMonitor?.isTranslationSelected() == true)
            }
            newProvider.register()
            this.provider = newProvider
            YLog.info(tag = TAG, msg = "Provider registered")
        }

        /**
         * 文件变更回调
         */
        override fun onFileChanged(event: Int, file: File) {
            val currentId = currentMusicId ?: return
            YLog.debug(tag = TAG, msg = "File changed: $file")
            if (file.name != currentId) return

            val metadata = MediaMetadataCache.getMetadataById(currentId) ?: return
            performSyncLoad(metadata)
        }

        /**
         * 响应歌曲元数据变更
         */
        fun onSongChanged(metadata: MediaMetadataCache.Metadata) {
            val newId = metadata.id
            if (currentMusicId == newId) return
            currentMusicId = newId
            performSyncLoad(metadata)
        }

        /**
         * 核心同步加载逻辑
         */
        private fun performSyncLoad(metadata: MediaMetadataCache.Metadata) {
            val id = metadata.id

            var targetSong = Song(
                id = id,
                name = metadata.title,
                artist = metadata.artist,
                duration = metadata.duration
            )

            // 尝试读取歌词文件并解析
            val rawFile = lyricFileObserver?.getFile(id)
            if (rawFile != null && rawFile.exists()) {
                try {
                    val jsonString = rawFile.readText()
                    val response = ResponseParser.parse(jsonString)
                    val parsedSong = response.toSong()

                    if (!parsedSong.lyrics.isNullOrEmpty() && !response.pureMusic) {
                        targetSong = parsedSong
                    }
                } catch (e: Exception) {
                    YLog.debug("Sync parse failed for $id: ${e.message}")
                }
            }

            setSong(targetSong)
        }

        private fun setSong(song: Song) {
            if (lastSong == song) return

            // 如果 ID 没变且歌词都是空的，跳过重复刷新
            if (song.lyrics.isNullOrEmpty() && lastSong?.id == song.id && lastSong?.lyrics.isNullOrEmpty()) {
                return
            }

            YLog.debug(msg = "setSong Sync: ${song.name} (lyrics: ${song.lyrics?.size ?: 0})")
            lastSong = song
            provider?.player?.setSong(song)
        }

        // --- 进度同步部分 ---

        private fun startSyncAction() {
            if (isPlaying) return
            isPlaying = true
            provider?.player?.setPlaybackState(true)
            resumeCoroutineTask()
        }

        private fun stopSyncAction() {
            if (!isPlaying) return
            isPlaying = false
            provider?.player?.setPlaybackState(false)
            pauseCoroutineTask()
        }

        private fun resumeCoroutineTask() {
            if (progressJob?.isActive == true) return
            progressJob = coroutineScope.launch {
                while (isActive && isPlaying) {
                    val pos = readPosition()
                    provider?.player?.setPosition(pos.toLong())
                    delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
                }
            }
        }

        private fun pauseCoroutineTask() {
            progressJob?.cancel()
            progressJob = null
        }

        private fun readPosition(): Int {
            return try {
                hotHooker.getCurrentTimeMethod?.invoke(null) as? Int ?: 0
            } catch (_: Exception) {
                0
            }
        }

        inner class HotHooker {
            private val unhooks = mutableListOf<YukiMemberHookCreator.MemberHookCreator.Result>()
            var getCurrentTimeMethod: Method? = null

            fun rehook(classLoader: ClassLoader) {
                unhooks.forEach { it.remove() }
                unhooks.clear()

                val playServiceClass =
                    "com.netease.cloudmusic.service.PlayService".toClass(classLoader)
                getCurrentTimeMethod = playServiceClass.getDeclaredMethod("getCurrentTime")

                val playServiceClassResolve = playServiceClass.resolve()

                unhooks += playServiceClassResolve
                    .firstMethod {
                        name = "onMetadataChanged"
                        parameterCount = 1
                    }
                    .hook {
                        after {
                            val bizMusicMeta = args[0] ?: return@after
                            YLog.debug(tag = TAG, msg = "Metadata changed: $bizMusicMeta")
                            val metadata = MediaMetadataCache.put(bizMusicMeta) ?: return@after
                            onSongChanged(metadata)
                        }
                    }

                unhooks += playServiceClassResolve
                    .firstMethod {
                        name = "onPlaybackStatusChanged"
                        parameters(Int::class)
                    }
                    .hook {
                        after {
                            val status = args[0] as? Int ?: return@after
                            YLog.debug(tag = TAG, msg = "Playback status changed: $status")
                            when (status) {
                                PlaybackState.STATE_PLAYING -> startSyncAction()
                                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> stopSyncAction()
                                else -> Unit
                            }
                        }
                    }
            }
        }
    }
}