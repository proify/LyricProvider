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
import io.github.proify.extensions.json
import io.github.proify.lyricon.cmprovider.xposed.Constants.ICON
import io.github.proify.lyricon.cmprovider.xposed.Constants.PROVIDER_PACKAGE_NAME
import io.github.proify.lyricon.cmprovider.xposed.PreferencesMonitor.PreferenceCallback
import io.github.proify.lyricon.cmprovider.xposed.download.DownloadCallback
import io.github.proify.lyricon.cmprovider.xposed.download.Downloader
import io.github.proify.lyricon.cmprovider.xposed.parser.LocalLyricCache
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.yrckit.download.response.LyricResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import org.luckypray.dexkit.DexKitBridge
import java.io.File

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

    private class PlayProgressHooker : LyricFileObserver.FileObserverCallback, DownloadCallback {
        private var provider: LyriconProvider? = null
        private var lastSong: Song? = null
        private val hotHooker = HotHooker()
        private var currentMusicId: Long? = null
        private var lyricFileObserver: LyricFileObserver? = null

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

            hookMediaSession()
        }

        private fun hookMediaSession() {
            "android.media.session.MediaSession".toClass()
                .resolve()
                .apply {
                    firstMethod {
                        name = "setPlaybackState"
                        parameters(PlaybackState::class.java)
                    }.hook {
                        after {
                            val state = args[0] as? PlaybackState ?: return@after
                            provider?.player?.setPlaybackState(state)
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

        override fun onDownloadFinished(id: Long, response: LyricResponse) {
            YLog.debug(tag = TAG, msg = "Download finished: $id")
            writeToLocalLyricCache(id, response)
        }

        @OptIn(ExperimentalSerializationApi::class)
        private fun writeToLocalLyricCache(id: Long, response: LyricResponse) {
            val context = appContext ?: return

            val outputFile = File(Constants.getDownloadLyricDirectory(context), id.toString())
            val cache = LocalLyricCache(
                musicId = id,
                lrc = response.lrc?.lyric,
                lrcTranslateLyric = response.tlyric?.lyric,
                yrc = response.yrc?.lyric,
                yrcTranslateLyric = response.ytlrc?.lyric,
                pureMusic = response.pureMusic,
            )

            outputFile.outputStream().use { outputStream ->
                json.encodeToStream(cache, outputStream)
            }

            loadLyricFile("network", outputFile)
        }

        override fun onDownloadFailed(id: Long, e: Exception) {
            YLog.error(tag = TAG, msg = "Download failed: $id, e=$e")
        }

        /**
         * 监听文件回调
         */
        override fun onFileChanged(event: Int, file: File) {
            loadLyricFile("localCache", file)
        }

        fun loadLyricFile(source: String, file: File) {
            val currentId = currentMusicId ?: return
            if (file.name != currentId.toString()) return

            YLog.debug(tag = TAG, msg = "Load lyric file: $source, file=$file")
            val metadata = MediaMetadataCache.getMetadataById(currentId) ?: return
            performSyncLoad(metadata, file)
        }

        /**
         * 响应歌曲元数据变更
         */
        fun onSongChanged(metadata: MediaMetadataCache.Metadata) {
            val newId = metadata.id
            if (currentMusicId == newId) return
            currentMusicId = newId

            val cacheFile = lyricFileObserver?.getFile(newId)
            if (cacheFile != null && cacheFile.exists()) {
                loadLyricFile("localCache", cacheFile)
            } else {
                Downloader.download(newId, this)
            }
        }

        /**
         * 核心同步加载逻辑
         */
        private fun performSyncLoad(metadata: MediaMetadataCache.Metadata, rawFile: File?) {
            val id = metadata.id

            var targetSong = Song(
                id = id.toString(),
                name = metadata.title,
                artist = metadata.artist,
                duration = metadata.duration
            )

            if (rawFile?.exists() == true) {
                try {
                    val jsonString = rawFile.readText()
                    val response = json.decodeFromString<LocalLyricCache>(jsonString)
                    val parsedSong = response.toSong()

                    if (!parsedSong.lyrics.isNullOrEmpty() && !response.pureMusic) {
                        targetSong = parsedSong
                    }
                } catch (e: Exception) {
                    YLog.error("Sync parse failed for $id: ${e.message}", e = e)
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

        inner class HotHooker {
            private val unhooks = mutableListOf<YukiMemberHookCreator.MemberHookCreator.Result>()

            fun rehook(classLoader: ClassLoader) {
                unhooks.forEach { it.remove() }
                unhooks.clear()

                val playServiceClass =
                    "com.netease.cloudmusic.service.PlayService".toClass(classLoader)

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
            }
        }
    }
}