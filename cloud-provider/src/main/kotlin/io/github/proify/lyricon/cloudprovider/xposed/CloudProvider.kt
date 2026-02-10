/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cloudprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.cloudlyric.ProviderLyrics
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

object CloudProvider : YukiBaseHooker(), DownloadCallback {
    private const val TAG: String = "CloudProvider"

    private var provider: LyriconProvider? = null
    private var lastMediaSignature: String? = null

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "进程: $processName")

        onAppLifecycle {
            onCreate {
                initProvider()
            }
        }

        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON),
            processName = processName
        ).apply { register() }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState
                    provider?.player?.setPlaybackState(state)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after

                    val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)

                    val signature = calculateSignature(id, title, artist, album)
                    if (signature == lastMediaSignature) {
                        YLog.debug(tag = TAG, msg = "Same metadata, skip")
                        return@after
                    }
                    lastMediaSignature = signature

                    YLog.debug(
                        tag = TAG,
                        msg = "Metadata: id=$id, title=$title, artist=$artist, album=$album"
                    )

                    provider?.player?.setSong(
                        Song(
                            name = title,
                            artist = artist,
                        )
                    )

                    DownloadManager.cancel()

                    YLog.debug(
                        tag = TAG,
                        msg = "Searching lyrics... trackName=$title, artist=$artist, album=$album"
                    )
                    DownloadManager.search(this@CloudProvider) {
                        trackName = title
                        artistName = artist
                        albumName = album
                        perProviderLimit = 5
                        maxTotalResults = 1
                    }
                }
            }
        }
    }

    private fun calculateSignature(vararg data: String?): String {
        return data.joinToString("") { it?.hashCode()?.toString() ?: "0" }.hashCode().toString()
    }

    override fun onDownloadFinished(response: List<ProviderLyrics>) {
        YLog.debug(tag = TAG, msg = "Download finished: $response")
        val song = response.firstOrNull()?.lyrics?.toSong()
        provider?.player?.setSong(song)
    }

    override fun onDownloadFailed(e: Exception) {
        YLog.error(tag = TAG, msg = "Download failed: ${e.message}")
    }

    private fun LyricsResult.toSong() = Song().apply {
        name = trackName
        artist = artistName
        lyrics = rich
        duration = rich.lastOrNull()?.end ?: 0L
    }
}