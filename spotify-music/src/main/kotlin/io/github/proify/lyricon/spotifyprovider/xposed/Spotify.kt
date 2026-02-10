/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.extensions.toPairMap
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.spotifyprovider.xposed.api.NoFoundLyricException
import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi
import io.github.proify.lyricon.spotifyprovider.xposed.api.SpotifyApi.jsonParser
import io.github.proify.lyricon.spotifyprovider.xposed.api.response.LyricResponse
import java.util.Locale

object Spotify : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "SpotifyProvider"
    private var lyriconProvider: LyriconProvider? = null
    private var trackId: String? = null
    private var lastSong: Song? = null

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "正在注入进程: $processName")

        onAppLifecycle {
            onCreate { initProvider() }
        }
        hookMediaSession()
        hookOkHttp()
    }

    private fun hookOkHttp() {
        "okhttp3.Headers".toClass()
            .resolve()
            .firstConstructor()
            .hook {
                after {
                    val arg = args[0] as? Array<*> ?: return@after
                    arg.toPairMap().forEach { (key, value) ->
                        val keyLowercase = key.lowercase(Locale.ENGLISH)
                        if (keyLowercase in SpotifyApi.keysRequired) {
                            SpotifyApi.headers[keyLowercase] = value
                        }
                    }
                }
            }
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
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
                    val state = (args[0] as? PlaybackState) ?: return@after
                    lyriconProvider?.player?.setPlaybackState(state)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after

                    val data = MetadataCache.save(metadata)

                    if (data == null) {

                        //处理其它通知（比如广告）
                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        if (title.isNotBlank() || artist.isNotBlank()) {
                            YLog.info(tag = TAG, msg = "Invalid track info, using title and artist")
                            setPlaceholder(title, artist)
                        }

                        return@after
                    }

                    val id = data.id
                    if (id == this@Spotify.trackId) return@after
                    this@Spotify.trackId = id

                    onTrackIdChanged(data)
                }
            }
        }
    }

    private fun setPlaceholder(title: String?, artist: String?) {
        setSong(Song(name = title, artist = artist))
    }

    private fun onTrackIdChanged(data: Metadata) {
        val (id, title, artist) = data
        val cache = appContext?.let { DiskCache.get(it, id) }
        if (cache != null) {
            applyResponse(id, cache)
            return
        }

        setPlaceholder(title, artist)
        Downloader.download(id, this)
    }

    override fun onDownloadFinished(id: String, response: String) {
        applyResponse(id, response)
        appContext?.let { DiskCache.put(it, id, response) }
    }

    private fun applyResponse(id: String, response: String) {
        val lyricResponse =
            runCatching { jsonParser.decodeFromString<LyricResponse>(response) }.getOrNull()
        if (lyricResponse != null) {
            val song = lyricResponse.toSong(id)
            if (song != lastSong) setSong(song)
        }
    }

    override fun onDownloadFailed(id: String, e: Exception) {
        if (e is NoFoundLyricException) {
            YLog.debug(tag = TAG, msg = e.message)
        } else {
            YLog.error(tag = TAG, msg = "Failed to fetch lyric for $id", e = e)
        }
    }

    private fun setSong(song: Song) {
        lastSong = song
        lyriconProvider?.player?.setSong(song)
    }
}