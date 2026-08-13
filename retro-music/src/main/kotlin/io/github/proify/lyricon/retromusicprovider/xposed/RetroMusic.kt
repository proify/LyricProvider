/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.retromusicprovider.xposed

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.io.File

/**
 * Lyricon provider for Retro Music Player 6.6.x.
 * Using Heuristic Depth-2 Memory Dump & Direct File I/O Bypass.
 */
object RetroMusic : YukiBaseHooker() {
    private const val TAG = Constants.LOG_TAG
    private const val MUSIC_SERVICE = "code.name.monkey.retromusic.service.MusicService"
    private const val RETRO_SONG = "code.name.monkey.retromusic.model.Song"
    private const val LYRIC_UTIL = "code.name.monkey.retromusic.util.LyricUtil"

    private var provider: LyriconProvider? = null
    private var lastSongKey: String? = null
    private var lastLyricsSongKey: String? = null
    private var lastNoLyricsSongKey: String? = null
    
    private var musicServiceInstance: Any? = null

    override fun onHook() {
        YLog.info(tag = TAG, msg = "Starting Retro Music provider (Heuristic Depth-2 Mode)")

        onAppLifecycle {
            onCreate { initProvider(this) }
            onTerminate {
                provider?.unregister()
                provider = null
                lastSongKey = null
                lastLyricsSongKey = null
                lastNoLyricsSongKey = null
                musicServiceInstance = null
            }
        }

        hookRetroMusicService()
        hookMediaSession()
    }

    private fun initProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = Constants.MUSIC_PACKAGE_NAME,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply { register() }
        YLog.info(tag = TAG, msg = "Lyricon provider registered")
    }

    private fun hookRetroMusicService() {
        val serviceClass = MUSIC_SERVICE.toClassOrNull() ?: return
        serviceClass.declaredMethods.firstOrNull { it.name == "onCreate" }?.hook {
            after { musicServiceInstance = instance }
        }
        serviceClass.declaredMethods.firstOrNull { it.name == "onStartCommand" }?.hook {
            after { musicServiceInstance = instance }
        }
    }

    private fun hookMediaSession() {
        try {
            val mediaSessionClass = Class.forName("android.media.session.MediaSession")
            mediaSessionClass.declaredMethods.firstOrNull { 
                it.name == "setPlaybackState" && it.parameterTypes.size == 1 && it.parameterTypes[0] == PlaybackState::class.java
            }?.hook {
                after {
                    val state = args[0] as? PlaybackState ?: return@after
                    provider?.player?.setPlaybackState(state)
                }
            }

            mediaSessionClass.declaredMethods.firstOrNull { it.name == "setMetadata" }?.hook {
                after {
                    val success = forceUpdateFromServiceInstance()
                    if (!success) {
                        val metadata = args[0] as? MediaMetadata
                        if (metadata != null) fallbackToMediaMetadata(metadata)
                    }
                }
            }
        } catch (e: Throwable) {
            YLog.error(tag = TAG, msg = "Failed to hook MediaSession", e = e)
        }
    }

    private fun forceUpdateFromServiceInstance(): Boolean {
        val service = musicServiceInstance ?: return false
        val songClass = RETRO_SONG.toClassOrNull() ?: return false
        
        try {
            var currentClass: Class<*>? = service.javaClass
            val checkedInstances = mutableSetOf<Int>()
            
            while (currentClass != null && currentClass != Any::class.java) {
                for (field in currentClass.declaredFields) {
                    if (field.type.isPrimitive || field.type.isArray || field.type.name.startsWith("java.")) continue
                    field.isAccessible = true
                    val engineObj = field.get(service) ?: continue
                    if (!checkedInstances.add(System.identityHashCode(engineObj))) continue
                    
                    var engineClass: Class<*>? = engineObj.javaClass
                    while (engineClass != null && engineClass != Any::class.java) {
                        val songField = engineClass.declaredFields.firstOrNull { it.type == songClass }
                        if (songField != null) {
                            songField.isAccessible = true
                            val song = songField.get(engineObj)
                            if (song != null) {
                                updateCurrentSongFromRetro(song)
                                return true
                            }
                        }
                        engineClass = engineClass.superclass
                    }
                }
                currentClass = currentClass.superclass
            }
            return false
        } catch (e: Throwable) {
            return false
        }
    }

    private fun fallbackToMediaMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        
        if (title.isNullOrBlank()) return
        
        val songKey = "FALLBACK|$title|$artist|$duration"
        if (songKey == lastSongKey) return
        lastSongKey = songKey
        lastLyricsSongKey = null
        lastNoLyricsSongKey = null
        
        provider?.player?.setSong(Song(id = title.hashCode().toString(), name = title, artist = artist, duration = duration))
    }

    private fun updateCurrentSongFromRetro(retroSong: Any) {
        try {
            val id = getLong(retroSong, "getId")
            val title = getString(retroSong, "getTitle") ?: getString(retroSong, "title")
            val artist = getString(retroSong, "getArtistName") ?: getString(retroSong, "artistName")
            val duration = getLong(retroSong, "getDuration")
            val data = getString(retroSong, "getData") ?: getString(retroSong, "data")

            if (id == -1L || title.isNullOrBlank()) return

            val songKey = "$id|$title|$artist|$duration|$data"
            if (songKey != lastSongKey) {
                lastSongKey = songKey
                lastLyricsSongKey = null
                lastNoLyricsSongKey = null
                provider?.player?.setSong(Song(id = id.toString(), name = title, artist = artist, duration = duration))
                YLog.info(tag = TAG, msg = "Current song: $title - $artist")
            } else if (songKey == lastLyricsSongKey) {
                return
            }

            val rawLyrics = loadRetroMusicLyrics(retroSong, data)
            if (rawLyrics.isNullOrBlank()) {
                if (songKey != lastNoLyricsSongKey) {
                    lastNoLyricsSongKey = songKey
                    YLog.info(tag = TAG, msg = "No local/embedded lyrics: $title - $artist")
                }
                return
            }

            val document = EnhanceLrcParser.parse(rawLyrics, duration)
            val lines = document.lines.filter { !it.text.isNullOrBlank() }
            if (lines.isNotEmpty()) {
                provider?.player?.setSong(Song(id = id.toString(), name = title, artist = artist, duration = duration, lyrics = lines))
                lastLyricsSongKey = songKey
                YLog.info(tag = TAG, msg = "Lyrics loaded: $title - $artist (${lines.size} lines)")
            }
        } catch (e: Throwable) {
            YLog.error(tag = TAG, msg = "Failed to update Retro Music song", e = e)
        }
    }

    private fun loadRetroMusicLyrics(song: Any, audioPath: String?): String? {
        // 1. 尝试使用反射读取内嵌歌词（可能被混淆）
        try {
            val embedded = getString(song, "getEmbeddedSyncedLyrics")
            if (!embedded.isNullOrBlank()) return embedded
        } catch (e: Throwable) {}

        // 2. 尝试使用反射获取歌词文件（可能被混淆）
        try {
            val songClass = RETRO_SONG.toClassOrNull()
            val utilClass = LYRIC_UTIL.toClassOrNull() ?: "${LYRIC_UTIL}Kt".toClassOrNull()
            if (songClass != null && utilClass != null) {
                val isSingleton = utilClass.declaredFields.any { it.name == "INSTANCE" }
                val invokeInstance = if (isSingleton) utilClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) else null
                val syncedFileMethod = utilClass.declaredMethods.firstOrNull { it.name == "getSyncedLyricsFile" && it.parameterTypes.size == 1 && it.parameterTypes[0] == songClass }
                val syncedFile = syncedFileMethod?.apply { isAccessible = true }?.invoke(invokeInstance, song) as? File
                if (syncedFile?.isFile == true) return syncedFile.readText(Charsets.UTF_8)
            }
        } catch (e: Throwable) {}

        // 3. 【终极绝杀】：无视代码混淆，直接通过文件绝对路径硬读同级目录的 .lrc 歌词文件！
        if (!audioPath.isNullOrBlank()) {
            try {
                val audioFile = File(audioPath)
                val parent = audioFile.parentFile
                val baseName = audioFile.nameWithoutExtension

                if (parent != null && parent.exists()) {
                    // 同时兼容 .lrc, .LRC 甚至 .txt 后缀
                    val candidates = listOf(
                        File(parent, "$baseName.lrc"),
                        File(parent, "$baseName.LRC"),
                        File(parent, "$baseName.txt")
                    )

                    for (lrcFile in candidates) {
                        if (lrcFile.exists() && lrcFile.isFile) {
                            YLog.info(tag = TAG, msg = "Bypass Success: Directly read physical file -> ${lrcFile.absolutePath}")
                            return lrcFile.readText(Charsets.UTF_8)
                        }
                    }
                }
            } catch (e: Throwable) {
                YLog.error(tag = TAG, msg = "Direct File I/O Failed", e = e)
            }
        }
        
        return null
    }

    private fun callFileMethod(obj: Any, getter: String): File? = try { XposedHelpers.callMethod(obj, getter) as? File } catch (_: Throwable) { null }
    private fun getString(obj: Any, getter: String): String? = try { XposedHelpers.callMethod(obj, getter)?.toString() } catch (_: Throwable) { null }
    private fun getLong(obj: Any, getter: String): Long = try { (XposedHelpers.callMethod(obj, getter) as? Number)?.toLong() ?: 0L } catch (_: Throwable) { 0L }
}
